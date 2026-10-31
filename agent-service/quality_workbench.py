"""Export validated offline evaluation material for the web workbench. No network."""
from copy import deepcopy
import json
from quality_evaluation import canonical_hash, index_rows, score


def build_workbench(dataset, responses, reviews, receipts=None):
    checked = score(dataset, responses, reviews)
    allowed = {case['id'] for case in dataset['cases']}
    response_map = index_rows(responses['responses'], allowed)
    review_map = index_rows(reviews['reviews'], allowed)
    receipt_map = index_rows(receipts['cases'], allowed) if receipts else {}
    states = {row['scenarioId']: row for row in checked['cases']}
    cases = []
    for case in dataset['cases']:
        case_id = case['id']; body = case['input']; state = states[case_id]
        response = response_map.get(case_id)
        report_hash = canonical_hash(response['report']) if response else None
        review = deepcopy(review_map.get(case_id) or {
            'scenarioId': case_id, 'inputHash': case['inputHash'], 'annotationStatus': 'PENDING',
            'annotationReviewer': None, 'relevantEvidence': [], 'qualityReviewer': None,
            'factsVsInferencesScore': None, 'expressesUncertainty': None, 'unsafeRecommendation': None})
        if review.get('reportHash') != report_hash or state['guard'] != 'ACCEPTED':
            for key in ('qualityReviewer', 'factsVsInferencesScore', 'expressesUncertainty',
                        'unsafeRecommendation', 'actionabilityScore', 'reviewerNotes'):
                review[key] = None if key != 'reviewerNotes' else ''
        review['reportHash'] = report_hash
        receipt = receipt_map.get(case_id)
        safe_receipt = None
        if receipt and receipt.get('inputHash') == case['inputHash']:
            tokens = (receipt.get('usage') or {}).get('total_tokens')
            amount = receipt.get('peakUncachedCostEstimateUsd')
            # Receipt labels are fixed codes; never copy provider text or arbitrary metadata.
            error = receipt.get('validationErrorCode') or receipt.get('error')
            safe_receipt = {
                'requestAttempts': receipt.get('requestAttempts') if type(receipt.get('requestAttempts')) is int and receipt['requestAttempts'] >= 0 else None,
                'status': receipt['status'] if receipt.get('status') in {'ACCEPTED', 'FAILED'} else 'UNKNOWN',
                'errorCode': error if isinstance(error, str) and error.replace('_', '').isalnum() and len(error) < 80 else None,
                'totalTokens': tokens if type(tokens) is int and tokens >= 0 else None,
                'estimatedCostUsd': amount if type(amount) in (int, float) and 0 <= amount < 100 else None}
        evidence = [{'id': 'check:'+c['runId'], 'text': f"{c['completedAt']} · {c['outcome']} · HTTP {c['statusCode']} · {c['latencyMs']} ms"} for c in body['checks']]
        evidence += [{'id': 'runbook:'+s['sectionId'], 'text': s['text']} for s in body['runbooks']]
        cases.append({'id': case_id, 'title': case['title'], 'inputHash': case['inputHash'],
                      'reportHash': report_hash, 'guard': state['guard'], 'evidence': evidence,
                      'expectations': case['expectations'], 'receipt': safe_receipt,
                      'report': response['report'] if response and state['guard'] == 'ACCEPTED' else None,
                      'reportJson': json.dumps(response['report'], sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False)
                          if response and state['guard'] == 'ACCEPTED' else None,
                      'review': review})
    return {'schemaVersion': 1, 'kind': 'dawnwatch-quality-workbench', 'sampleMode': 'synthetic',
            'datasetVersion': dataset['datasetVersion'], 'realModelVerified': False,
            'provenance': 'offline-import-unverified', 'cases': cases}
