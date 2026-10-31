from copy import deepcopy
import csv
import json
import pytest
from test_quality_evaluation import dataset, artifacts
from quality_evaluation import canonical_hash, score
from quality_review import review_package, import_reviews
from evaluate import main


def test_package_binds_hashes_preserves_annotation_and_unchanged_ratings(tmp_path):
    data = dataset(); responses, reviews = artifacts(data)
    reviews['reviews'][0].update(annotationStatus='PENDING',annotationReviewer=None,qualityReviewer=None,
                               factsVsInferencesScore=None,expressesUncertainty=None,unsafeRecommendation=None,reportHash=None)
    review_package(data,responses,reviews,tmp_path/'package')
    bound = json.loads((tmp_path/'package/reviews.json').read_text(encoding='utf-8'))
    first = bound['reviews'][0]
    assert first['reportHash'] == canonical_hash(responses['responses'][0]['report'])
    assert first['annotationStatus'] == 'PENDING' and first['qualityReviewer'] is None
    assert bound['reviews'][1] == reviews['reviews'][1]
    assert (tmp_path/'package/review.csv').exists() and (tmp_path/'package/reports.md').exists()
    with pytest.raises(ValueError): review_package(data,responses,reviews,tmp_path/'package')


def test_changed_report_resets_only_report_judgments_and_does_not_render_markdown(tmp_path):
    data = dataset(); responses,reviews = artifacts(data)
    responses['responses'][0]['report']['summary'] = '![untrusted](https://invalid.example/track)\n<script>alert(1)</script>'
    review_package(data,responses,reviews,tmp_path/'package')
    bound=json.loads((tmp_path/'package/reviews.json').read_text(encoding='utf-8'))['reviews'][0]
    assert bound['annotationStatus']=='APPROVED' and bound['qualityReviewer'] is None
    assert bound['factsVsInferencesScore'] is None and bound['expressesUncertainty'] is None
    markdown=(tmp_path/'package/reports.md').read_text(encoding='utf-8')
    assert '\n![' not in markdown and '\n<script>' not in markdown


def test_csv_round_trip_and_human_scores_are_explicit(tmp_path):
    data=dataset();responses,reviews=artifacts(data)
    reviews['reviews'][0]['qualityReviewer']='=SYNTHETIC_FORMULA()'
    review_package(data,responses,reviews,tmp_path/'package')
    text=(tmp_path/'package/review.csv').read_text(encoding='utf-8-sig')
    assert "'=SYNTHETIC_FORMULA()" in text
    loaded=import_reviews(data,responses,tmp_path/'package/review.csv')
    assert loaded==reviews
    assert score(data,responses,loaded)['status']=='PASS'


@pytest.mark.parametrize('field,value',[('inputHash','0'*64),('reportHash','0'*64),('scenarioId','foreign'),('factsVsInferencesScore','6'),('expressesUncertainty','maybe')])
def test_csv_stale_or_invalid_judgments_are_rejected(tmp_path,field,value):
    data=dataset();responses,reviews=artifacts(data)
    review_package(data,responses,reviews,tmp_path/'package')
    path=tmp_path/'package/review.csv'
    with path.open(encoding='utf-8-sig',newline='') as f:
        reader=csv.DictReader(f);rows=list(reader);fields=reader.fieldnames
    rows[0][field]=value
    with path.open('w',encoding='utf-8-sig',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=fields);writer.writeheader();writer.writerows(rows)
    with pytest.raises(ValueError): import_reviews(data,responses,path)


def test_guard_rejected_report_is_not_copied_into_human_package(tmp_path):
    data=dataset();responses,reviews=artifacts(data)
    responses['responses'][0]['report']['summary']='password=synthetic-do-not-export'
    review_package(data,responses,reviews,tmp_path/'package')
    assert 'synthetic-do-not-export' not in (tmp_path/'package/reports.md').read_text(encoding='utf-8')


def test_review_and_import_cli_complete_fixture_workflow(tmp_path):
    data=dataset();responses,reviews=artifacts(data)
    for name,value in [('dataset',data),('responses',responses),('reviews',reviews)]:
        (tmp_path/(name+'.json')).write_text(json.dumps(value),encoding='utf-8')
    common=['--dataset',str(tmp_path/'dataset.json'),'--responses',str(tmp_path/'responses.json')]
    assert main(['review',*common,'--reviews',str(tmp_path/'reviews.json'),'--output',str(tmp_path/'package')])==0
    assert main(['import-reviews',*common,'--csv',str(tmp_path/'package/review.csv'),'--output',str(tmp_path/'imported.json')])==0
    loaded=json.loads((tmp_path/'imported.json').read_text(encoding='utf-8'))
    assert loaded==reviews
    assert main(['import-reviews',*common,'--csv',str(tmp_path/'package/review.csv'),'--output',str(tmp_path/'imported.json')])==2


def test_literal_apostrophe_reviewer_name_survives_csv_round_trip(tmp_path):
    data=dataset();responses,reviews=artifacts(data)
    reviews['reviews'][0]['qualityReviewer']="'=SYNTHETIC_NAME()"
    review_package(data,responses,reviews,tmp_path/'package')
    assert import_reviews(data,responses,tmp_path/'package/review.csv')==reviews


def test_relevance_json_object_cannot_impersonate_a_list_of_human_labels(tmp_path):
    data=dataset();responses,reviews=artifacts(data)
    review_package(data,responses,reviews,tmp_path/'package')
    path=tmp_path/'package/review.csv'
    with path.open(encoding='utf-8-sig',newline='') as f:
        reader=csv.DictReader(f);rows=list(reader);fields=reader.fieldnames
    rows[0]['relevantEvidence']=json.dumps({ref:True for ref in reviews['reviews'][0]['relevantEvidence']})
    with path.open('w',encoding='utf-8-sig',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=fields);writer.writeheader();writer.writerows(rows)
    with pytest.raises(ValueError): import_reviews(data,responses,path)
