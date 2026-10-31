import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {parseWorkbench,importReviews,exportReviews,qualitySummary,qualityReviewed} from '../src/quality-center.ts'
const load=()=>parseWorkbench(JSON.parse(readFileSync(new URL('../src/quality-baseline.json',import.meta.url),'utf8')))
test('historical requests, accepted reports and missing human reviews are independent',async()=>{
 const bundle=await load(),summary=qualitySummary(bundle.cases)
 assert.equal(bundle.cases.length,30);assert.equal(summary.requestAttempts,2);assert.equal(summary.accepted,1)
 assert.equal(summary.qualityReviewed,0);assert.equal(summary.knownTokens,4561);assert.equal(summary.notRequested,28)
 assert.equal(bundle.cases.find(c=>c.id==='request-timeout').receipt.errorCode,'AGENT_RISK_UNDERESTIMATED')
 assert.equal(bundle.cases.find(c=>c.id==='request-timeout').report,null)
})
test('only complete deliberate judgments of an accepted report count as human reviewed',async()=>{
 const c=(await load()).cases[0];assert.equal(qualityReviewed(c),false)
 Object.assign(c.review,{annotationStatus:'APPROVED',annotationReviewer:'Reviewer',qualityReviewer:'Reviewer',factsVsInferencesScore:4,expressesUncertainty:true,unsafeRecommendation:false})
 assert.equal(qualityReviewed(c),true);c.review.qualityReviewer=' ';assert.equal(qualityReviewed(c),false)
})
test('review export restores unchanged judgments but rejects foreign and stale hashes atomically',async()=>{
 const bundle=await load(),saved=exportReviews(bundle.cases);saved.reviews[0].annotationStatus='APPROVED';saved.reviews[0].annotationReviewer='Reviewer'
 const result=importReviews(saved,bundle.cases);assert.equal(result[0].review.annotationReviewer,'Reviewer');assert.equal(bundle.cases[0].review.annotationReviewer,null)
 for(const key of ['inputHash','reportHash','scenarioId']){
  const invalid=structuredClone(saved);invalid.reviews[0][key]='wrong';assert.throws(()=>importReviews(invalid,bundle.cases))
 }
 saved.reviews[2].factsVsInferencesScore=4;assert.throws(()=>importReviews(saved,bundle.cases))
})
test('unknown token usage stays unknown and invalid bundles or ratings are rejected',async()=>{
 const bundle=await load();bundle.cases[0].receipt.totalTokens=null
 assert.equal(qualitySummary(bundle.cases).unknownUsage,1)
 for(const bad of [null,{}, {schemaVersion:1,kind:'dawnwatch-quality-workbench',cases:[]}])await assert.rejects(()=>parseWorkbench(bad))
 const saved=exportReviews(bundle.cases);saved.reviews[0].factsVsInferencesScore=6;assert.throws(()=>importReviews(saved,bundle.cases))
})


test('a changed report cannot retain an old human score or borrowed digest',async()=>{
 const raw=JSON.parse(readFileSync(new URL('../src/quality-baseline.json',import.meta.url),'utf8'))
 raw.cases[0].report.summary='Changed without review'
 await assert.rejects(()=>parseWorkbench(raw))
})
test('fractional provider counters are rejected instead of shown as request or token counts',async()=>{
 for(const field of ['requestAttempts','totalTokens']){
  const raw=JSON.parse(readFileSync(new URL('../src/quality-baseline.json',import.meta.url),'utf8'))
  raw.cases[0].receipt[field]=0.5
  await assert.rejects(()=>parseWorkbench(raw))
 }
})
