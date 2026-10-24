"""Pilot safety with fake providers only; never make paid requests."""
from copy import deepcopy
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
import json
from pathlib import Path
import pytest
import httpx
from agentops_agent.graph import build_graph
from langgraph.checkpoint.memory import InMemorySaver
from agentops_agent.provider import ProviderUnavailable
from quality_evaluation import build_dataset, canonical_hash
from evaluation_pilot import DEFAULT_CASES, make_plan, run_plan
from evaluate_live import main
from deepseek_check import OneRequestProvider


def data(): return build_dataset(datetime(2026, 10, 24, tzinfo=timezone.utc))


class FakeProvider:
    calls = 0
    mode = 'ok'
    instances = []

    def __init__(self, key):
        self.key = key; self.attempts = 0; self.usage = None; self.returned_model = 'fixture-model'
        self.instances.append(self)

    def analyze(self, invocation):
        type(self).calls += 1; self.attempts = 1
        if self.mode == 'failure': raise ProviderUnavailable('password=do-not-serialize')
        self.usage = None if self.mode == 'no-usage' else {'prompt_tokens': 1000, 'completion_tokens': 500, 'total_tokens': 1500}
        body = invocation.model_dump(mode='json'); body['modelName'] = 'mock'
        report = build_graph(InMemorySaver()).invoke({'input': body}, {'configurable': {'thread_id': body['runId']}})['result']
        report['modelName'] = invocation.modelName
        if self.mode == 'bad-report': report['summary'] = 'password=do-not-serialize'
        return report


@pytest.fixture(autouse=True)
def reset():
    FakeProvider.calls = 0; FakeProvider.mode = 'ok'; FakeProvider.instances = []


def test_plan_is_dry_run_bounded_and_rejects_edited_synthetic_data(tmp_path):
    plan = make_plan(data(), tmp_path/'plan')
    assert plan['scenarioIds'] == list(DEFAULT_CASES) and plan['requestLimit'] == 5
    assert plan['budgetUsd'] == .05 and plan['automaticRetry'] is False
    assert plan['reservedCostEstimateUsd'] <= plan['budgetUsd']
    assert FakeProvider.calls == 0
    altered = data(); altered['cases'][0]['input']['runbooks'][0]['text'] = 'private data'
    with pytest.raises(ValueError): make_plan(altered, tmp_path/'bad')
    with pytest.raises(ValueError): make_plan(data(), tmp_path/'plan')


@pytest.mark.parametrize('selected,budget', [(list(DEFAULT_CASES)+['healthy'], .05), (['http-503']*2,.05), (['foreign'],.05), (list(DEFAULT_CASES),.01)])
def test_invalid_selection_or_budget_is_rejected(tmp_path, selected, budget):
    with pytest.raises(ValueError): make_plan(data(), tmp_path/'bad', selected=selected, budget_usd=budget)
    assert FakeProvider.calls == 0


def test_run_requires_approved_hash_and_consumes_directory_once(tmp_path):
    directory = tmp_path/'once'; plan = make_plan(data(), directory)
    with pytest.raises(ValueError): run_plan(directory, 'synthetic-key', 'wrong', provider_factory=FakeProvider)
    assert FakeProvider.calls == 0
    result = run_plan(directory, 'synthetic-key', plan['planHash'], provider_factory=FakeProvider)
    assert result['status'] == 'COMPLETED' and result['requestAttempts'] == 5
    assert result['acceptedReports'] == 5 and result['realModelQualityMeasured'] is False
    assert all(p.key == '' for p in FakeProvider.instances)
    with pytest.raises(ValueError): run_plan(directory, 'synthetic-key', plan['planHash'], provider_factory=FakeProvider)
    assert FakeProvider.calls == 5
    assert 'synthetic-key' not in ''.join(p.read_text(encoding='utf-8') for p in directory.glob('*.json'))


@pytest.mark.parametrize('mode', ['failure','no-usage','bad-report'])
def test_any_uncertain_or_rejected_case_stops_without_retry(tmp_path, mode):
    directory = tmp_path/mode; plan = make_plan(data(), directory)
    FakeProvider.mode = mode
    result = run_plan(directory, 'synthetic-key', plan['planHash'], provider_factory=FakeProvider)
    assert result['status'] == 'STOPPED' and result['requestAttempts'] == 1
    assert FakeProvider.calls == 1
    with pytest.raises(ValueError): run_plan(directory, 'synthetic-key', plan['planHash'], provider_factory=FakeProvider)
    assert 'do-not-serialize' not in json.dumps(result)
    assert all(p.key == '' for p in FakeProvider.instances)


def test_tampered_plan_or_frozen_dataset_is_rejected_before_provider(tmp_path):
    directory = tmp_path/'tampered'; plan = make_plan(data(), directory)
    path = directory/'dataset.json'; frozen = json.loads(path.read_text(encoding='utf-8'))
    frozen['cases'][0]['input']['modelName'] = 'another-model'
    path.write_text(json.dumps(frozen),encoding='utf-8')
    with pytest.raises(ValueError): run_plan(directory,'synthetic-key',plan['planHash'],provider_factory=FakeProvider)
    assert FakeProvider.calls == 0


def test_cli_noninteractive_run_never_reads_a_key_or_calls_a_provider(tmp_path, monkeypatch):
    directory = tmp_path/'noninteractive'; plan = make_plan(data(), directory)
    monkeypatch.setattr('evaluate_live.interactive_input', lambda: False)
    def denied(*args): raise AssertionError('must not request Key')
    monkeypatch.setattr('evaluate_live.getpass', denied)
    assert main(['run','--directory',str(directory),'--approve-plan',plan['planHash']]) == 2
    assert not (directory/'started.json').exists()


def test_actual_adapter_five_request_contract_uses_only_official_mock_transport(tmp_path):
    directory=tmp_path/'adapter';plan=make_plan(data(),directory)
    seen=[]
    def respond(request):
        assert request.url == 'https://api.deepseek.com/chat/completions'
        body=json.loads(request.content)
        assert len(request.content) <= 12000 and body['max_tokens']==4096
        assert body['thinking']=={'type':'disabled'} and body['stream'] is False
        source=json.loads(body['messages'][1]['content']);seen.append(source['runId'])
        report={'summary':'仅整理合成检查，根因未知。','hypotheses':[],
                'evidenceIds':source['allowedEvidenceIds'],
                'nextSteps':[{'text':'人工核对检查历史。','risk':'LOW','requiresHumanReview':True}],
                'uncertainties':['没有日志及部署事实，不能确认根因。']}
        return httpx.Response(200,json={'model':'deepseek-flash','usage':{'prompt_tokens':1000,'completion_tokens':500,'total_tokens':1500},
               'choices':[{'finish_reason':'stop','message':{'content':json.dumps(report,ensure_ascii=False)}}]})
    result=run_plan(directory,'synthetic-key',plan['planHash'],provider_factory=lambda key:OneRequestProvider(key,transport=httpx.MockTransport(respond)))
    assert len(seen)==5 and len(set(seen))==5 and result['acceptedReports']==5
    assert result['status']=='COMPLETED' and result['requestAttempts']==5


def test_two_concurrent_runners_share_one_persistent_claim(tmp_path):
    directory=tmp_path/'concurrent';plan=make_plan(data(),directory);gate=Barrier(2)
    def attempt():
        gate.wait(timeout=5)
        try: return run_plan(directory,'synthetic-key',plan['planHash'],provider_factory=FakeProvider)['status']
        except (ValueError,FileExistsError): return 'BLOCKED'
    with ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(lambda _:attempt(),range(2)))
    assert sorted(results)==['BLOCKED','COMPLETED'] and FakeProvider.calls==5


def test_remaining_budget_is_reserved_before_next_request(tmp_path):
    directory=tmp_path/'budget';plan=make_plan(data(),directory,selected=DEFAULT_CASES[:2],budget_usd=.02)
    class HigherUsage(FakeProvider):
        def analyze(self,invocation):
            report=super().analyze(invocation)
            self.usage={'prompt_tokens':30000,'completion_tokens':4000,'total_tokens':34000}
            return report
    result=run_plan(directory,'synthetic-key',plan['planHash'],provider_factory=HigherUsage)
    assert result['status']=='STOPPED' and result['requestAttempts']==1
    assert result['stopReason']=='BUDGET_RESERVE_EXHAUSTED'


def test_empty_dataset_is_a_safe_validation_error_before_any_plan_is_created(tmp_path):
    broken=data();broken['cases']=[]
    with pytest.raises(ValueError): make_plan(broken,tmp_path/'empty')
    assert not (tmp_path/'empty').exists()
