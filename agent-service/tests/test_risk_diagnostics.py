"""Safe diagnostics and prompt contracts, not a replay of the discarded live report."""
import json
import httpx
import pytest
from agentops_agent.safety import RISKY, require_safe_output
from agentops_agent.provider import DeepSeekProvider
from deepseek_check import run_check, validation_details
from quality_evaluation import build_dataset
from evaluation_pilot import make_plan, run_plan
from test_evaluation_pilot import FakeProvider
from test_deepseek_check import response_for


def report(text, risk='LOW'):
    return {'summary':'合成诊断','uncertainties':['根因未知'], 'hypotheses':[],
            'nextSteps':[{'text':'人工核对检查历史。','risk':'LOW','requiresHumanReview':True},
                         {'text':text,'risk':risk,'requiresHumanReview':True}]}


def test_diagnostic_names_step_and_policy_category_without_retaining_text():
    text='人工重启 fixture-private-host，具体事项仅为离线样本。'
    with pytest.raises(ValueError,match='AGENT_RISK_UNDERESTIMATED') as caught:
        require_safe_output(report(text,'MEDIUM'))
    detail=validation_details(caught.value)
    assert detail['validationFields']==[['nextSteps',1,'risk']]
    assert detail['riskDiagnostics']=={'policyId':'operation-text-high-v1','stepIndex':1,
        'observedRisk':'MEDIUM','requiredRisk':'HIGH','operationCategory':'RESTART'}
    assert caught.value.args==('AGENT_RISK_UNDERESTIMATED',)
    assert 'fixture-private-host' not in json.dumps(detail) and text not in repr(caught.value)


@pytest.mark.parametrize('text,category',[
    ('不要执行重启，仅收集信息。','RESTART'),
    ('只读核对历史回滚记录。','ROLLBACK'),
    ('Use kubectl get for the synthetic observation.','PRIVILEGED_COMMAND'),
    ('人工删除合成资源。','DELETE'),
    ('人工修改配置。','CONFIGURATION_CHANGE'),
])
def test_existing_conservative_text_decisions_and_high_acceptance_are_preserved(text,category):
    assert RISKY.search(text)
    with pytest.raises(ValueError,match='AGENT_RISK_UNDERESTIMATED') as caught:
        require_safe_output(report(text,'LOW'))
    assert validation_details(caught.value)['riskDiagnostics']['operationCategory']==category
    assert require_safe_output(report(text,'HIGH')) is None


def test_secret_rejection_precedes_risk_details_and_plain_errors_stay_code_only():
    with pytest.raises(ValueError,match='AGENT_SENSITIVE_OUTPUT') as caught:
        require_safe_output(report('restart password=fixture-secret'))
    assert validation_details(caught.value)=={'validationErrorCode':'AGENT_SENSITIVE_OUTPUT'}
    assert validation_details(ValueError('AGENT_RISK_UNDERESTIMATED'))=={'validationErrorCode':'AGENT_RISK_UNDERESTIMATED'}
    assert validation_details(ValueError('password=fixture-secret'))=={'validationErrorCode':'UNCLASSIFIED_VALIDATION_ERROR'}
    assert require_safe_output(report('人工收集检查历史。','LOW')) is None


def test_deepseek_prompt_explains_exact_local_rule_without_changing_protocol():
    provider=DeepSeekProvider('deepseek-flash','fixture-key')
    body=provider._body('Fixture system prompt',{'checks':[],'runbooks':[]})
    text=body['messages'][0]['content']
    assert RISKY.pattern in text
    assert all(word in text for word in ('negated','historical','hypothetical','HIGH','never authorizes execution'))
    assert body['thinking']=={'type':'disabled'} and body['max_tokens']==4096
    assert body['response_format']=={'type':'json_object'} and 'tools' not in body


def test_one_request_check_exports_safe_rejection_location_and_usage():
    seen=[]
    def handle(request):
        seen.append(request)
        response=response_for(request)
        content=json.loads(response['choices'][0]['message']['content'])
        content['nextSteps'][0]['text']='只读核对历史重启记录 fixture-private-host'
        content['nextSteps'][0]['risk']='LOW'
        response['choices'][0]['message']['content']=json.dumps(content,ensure_ascii=False)
        return httpx.Response(200,json=response)
    result=run_check('fixture-key',transport=httpx.MockTransport(handle))
    assert result['status']=='FAIL' and result['requestAttempts']==len(seen)==1
    assert result['validationFields']==[['nextSteps',0,'risk']]
    assert result['riskDiagnostics']['operationCategory']=='RESTART'
    assert result['usage']['total_tokens']==1100
    assert all(text not in json.dumps(result) for text in ('fixture-private-host','fixture-key','只读核对历史重启记录'))
    assert 'report' not in result


def test_pilot_stops_once_and_persists_diagnostic_without_rejected_report(tmp_path):
    class RiskProvider(FakeProvider):
        calls=0
        instances=[]
        mode='ok'
        def analyze(self,invocation):
            value=super().analyze(invocation)
            value['nextSteps'][0]['text']='只读核对历史回滚记录 fixture-private-host'
            return value
    directory=tmp_path/'risk'
    plan=make_plan(build_dataset(),directory)
    result=run_plan(directory,'fixture-key',plan['planHash'],provider_factory=RiskProvider)
    assert result['status']=='STOPPED' and result['requestAttempts']==RiskProvider.calls==1
    receipt=result['cases'][0]
    assert receipt['riskDiagnostics']['operationCategory']=='ROLLBACK'
    assert receipt['validationFields']==[['nextSteps',0,'risk']]
    assert json.loads((directory/'responses.json').read_text(encoding='utf-8'))['responses']==[]
    assert 'fixture-private-host' not in (directory/'receipts.json').read_text(encoding='utf-8')
    with pytest.raises(ValueError,match='PILOT_ALREADY_STARTED'):
        run_plan(directory,'fixture-key',plan['planHash'],provider_factory=RiskProvider)
