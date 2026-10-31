"""Offline human review package and CSV import. Never creates human judgments."""
from copy import deepcopy
import csv
import json
from pathlib import Path
from quality_evaluation import canonical_hash, dump, index_rows, score

FIELDS = ['scenarioId','inputHash','reportHash','annotationStatus','annotationReviewer','relevantEvidence',
          'qualityReviewer','factsVsInferencesScore','expressesUncertainty','unsafeRecommendation']
QUALITY = ['qualityReviewer','factsVsInferencesScore','expressesUncertainty','unsafeRecommendation']


def csv_cell(value):
    text = '' if value is None else str(value)
    return "'"+text if text.startswith(("'",'=','+','-','@','\t','\r')) else text


def csv_value(text):
    return text[1:] if text.startswith(("''","'=","'+","'-","'@","'\t","'\r")) else text


def review_package(dataset, responses, reviews, directory):
    directory = Path(directory)
    if directory.exists(): raise ValueError('OUTPUT_EXISTS')
    allowed = {c['id'] for c in dataset['cases']}
    response_map = index_rows(responses['responses'], allowed)
    bound = deepcopy(reviews)
    for row in bound['reviews']:
        report = response_map.get(row['scenarioId'], {}).get('report')
        digest = canonical_hash(report) if report is not None else None
        if row.get('reportHash') != digest:
            for name in QUALITY: row[name] = None
        row['reportHash'] = digest
    checked = score(dataset, responses, bound)
    states = {c['scenarioId']: c for c in checked['cases']}
    lines = ['# Dawnwatch 报告审阅包', '', '导入来源未经认证；未填写的人工复核和评分继续保持待办。此页面不会执行模型建议。',
             '使用review.csv填写人工声明，保留场景ID和hash；事实/推断1–5分，布尔值使用true/false或是/否。', '']
    for case in dataset['cases']:
        case_id = case['id']; state = states[case_id]
        lines += ['## '+case_id, '', '护栏：'+state['guard'], '']
        report = response_map.get(case_id, {}).get('report')
        if report and state['guard'] == 'ACCEPTED':
            # Literal indented JSON: Markdown links, images and HTML from the model stay inert.
            excerpt = {k: report.get(k) for k in ('summary','hypotheses','evidenceIds','nextSteps','uncertainties')}
            lines += ['    '+line for line in json.dumps(excerpt,ensure_ascii=False,indent=2).splitlines()]
            lines.append('')
    directory.mkdir(parents=True, exist_ok=False)
    dump(directory/'reviews.json',bound)
    dump(directory/'score-before-review.json',checked)
    (directory/'reports.md').write_text('\n'.join(lines),encoding='utf-8')
    with (directory/'review.csv').open('w',encoding='utf-8-sig',newline='') as output:
        writer=csv.DictWriter(output,fieldnames=FIELDS);writer.writeheader()
        for row in bound['reviews']:
            values = row | {'relevantEvidence':json.dumps(row.get('relevantEvidence',[]),ensure_ascii=False)}
            writer.writerow({name:csv_cell(values.get(name)) for name in FIELDS})


def import_reviews(dataset, responses, csv_path):
    rows=[]
    with Path(csv_path).open(encoding='utf-8-sig',newline='') as source:
        reader=csv.DictReader(source)
        if reader.fieldnames != FIELDS: raise ValueError('INVALID_REVIEW_COLUMNS')
        for item in reader:
            if None in item or any(v is None for v in item.values()): raise ValueError('INVALID_REVIEW_ROW')
            row={name:csv_value(item[name]) for name in FIELDS}
            row['relevantEvidence']=json.loads(row['relevantEvidence'])
            if not isinstance(row['relevantEvidence'],list) or not all(isinstance(ref,str) for ref in row['relevantEvidence']):
                raise ValueError('INVALID_RELEVANCE_LABEL')
            for name in ('reportHash','annotationReviewer','qualityReviewer'): row[name]=row[name] or None
            number=row['factsVsInferencesScore']
            if number and number not in {'1','2','3','4','5'}: raise ValueError('INVALID_HUMAN_SCORE')
            row['factsVsInferencesScore']=int(number) if number else None
            for name in ('expressesUncertainty','unsafeRecommendation'):
                value=row[name].strip().lower()
                if value not in {'','true','false','是','否'}: raise ValueError('INVALID_HUMAN_BOOLEAN')
                row[name]=None if not value else value in {'true','是'}
            if row['annotationStatus'] not in {'PENDING','APPROVED'}: raise ValueError('INVALID_ANNOTATION_STATE')
            if row['annotationStatus']=='APPROVED' and not row['annotationReviewer']: raise ValueError('MISSING_ANNOTATION_REVIEWER')
            if any(row.get(name) is not None for name in QUALITY) and (not row['reportHash'] or not row['qualityReviewer']):
                raise ValueError('QUALITY_REVIEW_REQUIRES_REPORT_AND_REVIEWER')
            rows.append(row)
    result={'schemaVersion':1,'sampleMode':'synthetic','reviews':rows}
    if len(rows)!=len(dataset['cases']): raise ValueError('INCOMPLETE_REVIEW_SHEET')
    score(dataset,responses,result)
    return result
