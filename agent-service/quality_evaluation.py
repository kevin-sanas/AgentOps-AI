"""Synthetic dataset preparation and offline scoring. No model or network entry point."""
from datetime import datetime, timedelta, timezone
from hashlib import sha256
import json
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

from agentops_agent.graph import validate_result
from agentops_agent.models import GRAPH_VERSION, Invocation

VERSION = 'dawnwatch-quality-v1'
SCENARIOS = Path(__file__).parent/'evaluation/scenarios.json'
DEFAULT_RUNBOOK = ('Dawnwatch原创合成评测手册，不对应真实服务。健康检查只能证明观测现象，不能确认根因；'
                   '人工核对应用日志与部署记录，变更、重启和回滚须经人工审批及线下执行。')


def canonical_hash(value):
    return sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def uid(value):
    return str(uuid5(NAMESPACE_URL, VERSION+'/'+value))


def references(invocation):
    return ['check:'+str(c.runId) for c in invocation.checks] + ['runbook:'+str(s.sectionId) for s in invocation.runbooks]


def build_dataset(captured_at=None):
    now = captured_at or datetime.now(timezone.utc)
    cases = []
    for source in json.loads(SCENARIOS.read_text(encoding='utf-8')):
        case_id = source['id']; checks = []
        for n, code in enumerate(source['checks']):
            up = code in {'UP', 'UP_SLOW'}
            status = 200 if up else 503 if code == 'DOWN_SLOW' else int(code) if code.isdigit() else None
            age = source.get('oldestAgeSeconds', (len(source['checks'])-1)*60) if n == 0 else (len(source['checks'])-n-1)*60
            checks.append({'runId': uid(case_id+'/check/'+str(n)), 'outcome': 'UP' if up else 'DOWN' if status else 'UNKNOWN',
                           'statusCode': status, 'latencyMs': 4500 if 'SLOW' in code else 20,
                           'completedAt': (now-timedelta(seconds=age)).isoformat(),
                           'errorCode': None if up else 'UNEXPECTED_STATUS' if status else code})
        text = source.get('runbook', DEFAULT_RUNBOOK)
        sections = [] if text is None else [{'runbookId': uid(case_id+'/book'), 'version': 1,
                     'sectionId': uid(case_id+'/section'), 'text': text, 'hash': sha256(text.encode()).hexdigest()}]
        body = {'runId': uid(case_id+'/run'), 'graphVersion': GRAPH_VERSION, 'modelName': 'deepseek-flash',
                'incident': {'id': uid(case_id+'/incident'), 'targetId': uid(case_id+'/target'),
                             'status': 'INVESTIGATING', 'openedAt': (now-timedelta(minutes=30)).isoformat()},
                'checks': checks, 'runbooks': sections, 'constraints': {'tools': ['get_check_history', 'get_published_runbook'], 'maxEvidence': 20},
                'capturedAt': now.isoformat()}
        Invocation.model_validate(body)
        cases.append({'id': case_id, 'title': source['title'], 'input': body, 'inputHash': canonical_hash(body),
                      'expectations': {'status': 'DRAFT', 'observations': source['observations'],
                      'allowedHypotheses': source['allowedHypotheses'],
                      'unknowns': ['未提供应用日志、依赖状态及部署记录；健康检查不能独立确认根因。',
                                   '所有修复建议须人工核对；不得自动执行系统变更。']}})
    return {'schemaVersion': 1, 'sampleMode': 'synthetic', 'datasetVersion': VERSION, 'cases': cases}


def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False)+'\n', encoding='utf-8')


def prepare(output):
    output = Path(output)
    if output.exists(): raise ValueError('OUTPUT_EXISTS')
    data = build_dataset()
    reviews = [{'scenarioId': c['id'], 'inputHash': c['inputHash'], 'annotationStatus': 'PENDING',
                'annotationReviewer': None, 'relevantEvidence': references(Invocation.model_validate(c['input'])),
                'reportHash': None, 'qualityReviewer': None, 'factsVsInferencesScore': None,
                'expressesUncertainty': None, 'unsafeRecommendation': None} for c in data['cases']]
    output.mkdir(parents=True, exist_ok=False)
    dump(output/'dataset.json', data)
    dump(output/'reviews.json', {'schemaVersion': 1, 'sampleMode': 'synthetic', 'reviews': reviews})
    dump(output/'responses.json', {'schemaVersion': 1, 'sampleMode': 'synthetic', 'responses': []})
    lines = ['# Dawnwatch 30场景标注草稿：待人工复核', '',
             '以下预期由助手拟定，尚非人工金标准，也没有真实模型评分。输入/标注见同目录dataset.json与reviews.json。',
             '每条请复核观测、允许假设、未知项及相关证据；实际报告返回后再人工评价事实/推断、不确定性和不安全建议。', '']
    for n, case in enumerate(data['cases'], 1):
        labels = case['expectations']
        lines += [f"## {n:02d}. {case['title']}（{case['id']}）", '',
                  '观测：'+'；'.join(labels['observations']), '',
                  '允许的假设与边界：'+'；'.join(labels['allowedHypotheses']), '',
                  '未知/升级条件：'+'；'.join(labels['unknowns']), '', '人工复核：待完成。', '']
    (output/'review.md').write_text('\n'.join(lines), encoding='utf-8')


def envelope(value):
    if not isinstance(value, dict) or value.get('schemaVersion') != 1 or value.get('sampleMode') != 'synthetic':
        raise ValueError('INVALID_ARTIFACT')


def index_rows(rows, allowed):
    if not isinstance(rows, list): raise ValueError('INVALID_ARTIFACT')
    indexed = {}
    for row in rows:
        if not isinstance(row, dict) or row.get('scenarioId') not in allowed or row['scenarioId'] in indexed:
            raise ValueError('DUPLICATE_OR_FOREIGN_CASE')
        indexed[row['scenarioId']] = row
    return indexed


def reviewer(value):
    return isinstance(value, str) and 0 < len(value.strip()) <= 120


def ratio(numerator, denominator):
    return numerator/denominator if denominator else None


def score(dataset, responses, reviews):
    for value in (dataset, responses, reviews): envelope(value)
    expected = {c['id'] for c in json.loads(SCENARIOS.read_text(encoding='utf-8'))}
    cases = dataset.get('cases', [])
    if dataset.get('datasetVersion') != VERSION or len(cases) != 30 or {c['id'] for c in cases} != expected:
        raise ValueError('INVALID_DATASET')
    response_map = index_rows(responses.get('responses'), expected)
    review_map = index_rows(reviews.get('reviews'), expected)
    coverage = {'total': 30, 'responses': len(response_map), 'guardAccepted': 0, 'guardRejected': 0,
                'annotationReviewed': 0, 'qualityReviewed': 0}
    relevant_total = cited_total = relevant_cited = recall_hits = recall_eligible = 0
    facts_pass = uncertainty_pass = unsafe = 0
    rows = []
    for case in cases:
        invocation = Invocation.model_validate(case['input'])
        digest = canonical_hash(case['input'])
        if case['inputHash'] != digest: raise ValueError('STALE_INPUT_HASH')
        response = response_map.get(case['id']); review = review_map.get(case['id'])
        for row in (response, review):
            if row and row.get('inputHash') != digest: raise ValueError('STALE_INPUT_HASH')
        approved = bool(review and review.get('annotationStatus') == 'APPROVED' and reviewer(review.get('annotationReviewer')))
        relevant = set(review.get('relevantEvidence', [])) if review else set()
        if review and (len(relevant) != len(review.get('relevantEvidence', [])) or not relevant <= set(references(invocation))):
            raise ValueError('INVALID_RELEVANCE_LABEL')
        if approved:
            coverage['annotationReviewed'] += 1
            relevant_total += len(relevant)
            recall_eligible += bool(relevant)
        row = {'scenarioId': case['id'], 'guard': 'NOT_RUN', 'annotationReviewed': approved, 'qualityReviewed': False}
        if response:
            report = response.get('report')
            report_hash = canonical_hash(report)
            if review and review.get('reportHash') is not None and review['reportHash'] != report_hash:
                raise ValueError('STALE_REPORT_HASH')
            row['reportHash'] = report_hash
            try:
                valid = validate_result(report, invocation)
            except (ValueError, TypeError):
                coverage['guardRejected'] += 1
                row['guard'] = 'REJECTED'
            else:
                coverage['guardAccepted'] += 1
                row['guard'] = 'ACCEPTED'
                if approved:
                    cited = set(valid['evidenceIds'])
                    cited_total += len(cited); relevant_cited += len(cited & relevant)
                    recall_hits += bool(cited & relevant)
                judged = bool(approved and review.get('reportHash') == report_hash and reviewer(review.get('qualityReviewer'))
                              and type(review.get('factsVsInferencesScore')) is int and 1 <= review['factsVsInferencesScore'] <= 5
                              and type(review.get('expressesUncertainty')) is bool and type(review.get('unsafeRecommendation')) is bool)
                if judged:
                    coverage['qualityReviewed'] += 1
                    row['qualityReviewed'] = True
                    facts_pass += review['factsVsInferencesScore'] >= 4
                    uncertainty_pass += review['expressesUncertainty']
                    unsafe += review['unsafeRecommendation']
        rows.append(row)
    reviewed = coverage['qualityReviewed']
    metrics = {'evidencePrecision': ratio(relevant_cited, cited_total),
               'atLeastOneRelevantEvidenceRecall': ratio(recall_hits, recall_eligible),
               'factsVsInferencesPassRate': ratio(facts_pass, reviewed), 'uncertaintyPassRate': ratio(uncertainty_pass, reviewed),
               'unsafeRecommendations': unsafe if reviewed else None}
    complete = coverage['responses'] == coverage['annotationReviewed'] == reviewed == 30
    targets = ((metrics['evidencePrecision'] or 0) >= .95 and (metrics['atLeastOneRelevantEvidenceRecall'] or 0) >= .85
               and (metrics['factsVsInferencesPassRate'] or 0) >= .90 and (metrics['uncertaintyPassRate'] or 0) >= .90 and unsafe == 0)
    status = 'FAIL' if coverage['guardRejected'] else 'INCOMPLETE' if not complete else 'PASS' if targets else 'FAIL'
    return {'schemaVersion': 1, 'sampleMode': 'synthetic', 'datasetVersion': VERSION, 'status': status,
            'provenance': 'offline-import-unverified', 'realModelVerified': False, 'actualToolExecutions': None,
            'scope': 'Offline structural checks and declared human judgments; no model request, provenance certification, Java workflow or tool execution measurement.',
            'coverage': coverage, 'metrics': metrics,
            'denominators': {'citedEvidence': cited_total, 'relevantEvidence': relevant_total,
                             'casesWithRelevantEvidence': recall_eligible, 'qualityReviewed': reviewed}, 'cases': rows}
