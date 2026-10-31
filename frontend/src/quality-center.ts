export const QUALITY_PATH='/admin/quality'
export const guardLabels:Record<string,string>={ACCEPTED:'校验接受',REJECTED:'校验拒绝',NOT_RUN:'无报告可校验'}
export interface QualityReview {
 scenarioId:string;inputHash:string;reportHash:string|null;annotationStatus:'PENDING'|'APPROVED';annotationReviewer:string|null;
 relevantEvidence:string[];qualityReviewer:string|null;factsVsInferencesScore:number|null;expressesUncertainty:boolean|null;unsafeRecommendation:boolean|null
 actionabilityScore?:number|null;reviewerNotes?:string
}
export interface QualityReport {summary:string;hypotheses:{text:string;evidenceIds:string[]}[];evidenceIds:string[];nextSteps:{text:string;risk:string}[];uncertainties:string[]}
export interface QualityCase {
 id:string;title:string;inputHash:string;reportHash:string|null;guard:'ACCEPTED'|'REJECTED'|'NOT_RUN';
 evidence:{id:string;text:string}[];expectations:{observations:string[];allowedHypotheses:string[];unknowns:string[]};
 receipt:{requestAttempts:number|null;status:string;errorCode:string|null;totalTokens:number|null;estimatedCostUsd:number|null}|null;
 report:QualityReport|null;reportJson:string|null;review:QualityReview
}
export interface Workbench {schemaVersion:1;kind:'dawnwatch-quality-workbench';sampleMode:'synthetic';datasetVersion:string;realModelVerified:false;provenance:'offline-import-unverified';cases:QualityCase[]}
function object(value:unknown):Record<string,unknown>{if(!value||typeof value!=='object'||Array.isArray(value))throw Error('文件结构无效，请使用离线评测工具导出的 JSON。');return value as Record<string,unknown>}
function text(value:unknown,max=24000):value is string{return typeof value==='string'&&value.length<=max}
function strings(value:unknown):value is string[]{return Array.isArray(value)&&value.length<=100&&value.every(v=>text(v))}
function numberOrNull(value:unknown){return value===null||typeof value==='number'&&Number.isFinite(value)&&value>=0}
function countOrNull(value:unknown){return value===null||typeof value==='number'&&Number.isSafeInteger(value)&&value>=0}
function rating(value:unknown){return value===null||Number.isInteger(value)&&Number(value)>=1&&Number(value)<=5}
function name(value:unknown){return value===null||text(value,120)}
function ordered(value:unknown):unknown {
 if(Array.isArray(value))return value.map(ordered)
 if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).sort(([a],[b])=>a<b?-1:a>b?1:0).map(([k,v])=>[k,ordered(v)]))
 return value
}
export async function parseWorkbench(value:unknown):Promise<Workbench> {
 const b=object(value)
 if(b.schemaVersion!==1||b.kind!=='dawnwatch-quality-workbench'||b.sampleMode!=='synthetic'||b.realModelVerified!==false||b.provenance!=='offline-import-unverified'||!text(b.datasetVersion,100)||!Array.isArray(b.cases)||b.cases.length!==30)throw Error('需要包含 30 个合成场景的工作台包。')
 const ids=new Set<string>()
 for(const item of b.cases){
  const c=object(item),expected=object(c.expectations)
  if(!text(c.id,100)||ids.has(c.id)||!text(c.title,200)||!text(c.inputHash,64)||!/^[a-f0-9]{64}$/.test(c.inputHash)||!(c.reportHash===null||typeof c.reportHash==='string'&&/^[a-f0-9]{64}$/.test(c.reportHash))||!['ACCEPTED','REJECTED','NOT_RUN'].includes(String(c.guard))||!strings(expected.observations)||!strings(expected.allowedHypotheses)||!strings(expected.unknowns)||!Array.isArray(c.evidence)||c.evidence.length>20)throw Error('场景内容无效或 ID 重复。')
  ids.add(c.id)
  for(const e of c.evidence){const ref=object(e);if(!text(ref.id,100)||!text(ref.text))throw Error('证据结构无效。')}
  if(c.report!==null){
   const r=object(c.report)
   if(c.guard!=='ACCEPTED'||!c.reportHash||!text(r.summary)||!strings(r.evidenceIds)||!strings(r.uncertainties)||!Array.isArray(r.hypotheses)||!Array.isArray(r.nextSteps))throw Error('报告结构无效。')
   for(const h of r.hypotheses){const row=object(h);if(!text(row.text)||!strings(row.evidenceIds))throw Error('假设结构无效。')}
   for(const s of r.nextSteps){const row=object(s);if(!text(row.text)||!['LOW','MEDIUM','HIGH'].includes(String(row.risk)))throw Error('建议结构无效。')}
   if(!text(c.reportJson,65536)||JSON.stringify(ordered(JSON.parse(c.reportJson)))!==JSON.stringify(ordered(c.report)))throw Error('报告正文与冻结内容不同，不能沿用旧评分。')
   const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(c.reportJson)))).map(n=>n.toString(16).padStart(2,'0')).join('')
   if(digest!==c.reportHash)throw Error('报告内容摘要不匹配，不能沿用旧评分。')
  }else if(c.guard==='ACCEPTED'||c.reportJson!==null)throw Error('接受状态缺少报告或无效的报告正文。')
  if(c.receipt!==null){const r=object(c.receipt);if(!countOrNull(r.requestAttempts)||!countOrNull(r.totalTokens)||!numberOrNull(r.estimatedCostUsd)||!['ACCEPTED','FAILED','UNKNOWN'].includes(String(r.status))||!(r.errorCode===null||text(r.errorCode,80)))throw Error('请求回执结构无效。')}
 }
 const bundle=b as unknown as Workbench
 return {...bundle,cases:importReviews({schemaVersion:1,sampleMode:'synthetic',reviews:bundle.cases.map(c=>c.review)},bundle.cases)}
}
export function qualityReviewed(c:QualityCase){const r=c.review;return c.guard==='ACCEPTED'&&r.annotationStatus==='APPROVED'&&!!r.annotationReviewer?.trim()&&!!r.qualityReviewer?.trim()&&r.reportHash===c.reportHash&&r.factsVsInferencesScore!==null&&typeof r.expressesUncertainty==='boolean'&&typeof r.unsafeRecommendation==='boolean'}
export function qualitySummary(cases:QualityCase[]){
 return {accepted:cases.filter(c=>c.guard==='ACCEPTED').length,qualityReviewed:cases.filter(qualityReviewed).length,
  requestAttempts:cases.reduce((s,c)=>s+(c.receipt?.requestAttempts??0),0),notRequested:cases.filter(c=>!c.receipt&&!c.reportHash).length,
  unknownUsage:cases.filter(c=>c.receipt&&c.receipt.totalTokens===null).length,knownTokens:cases.reduce((s,c)=>s+(c.receipt?.totalTokens??0),0)}
}
export function exportReviews(cases:QualityCase[]){return {schemaVersion:1,sampleMode:'synthetic',reviews:JSON.parse(JSON.stringify(cases.map(c=>c.review))) as QualityReview[]}}
export function importReviews(value:unknown,cases:QualityCase[]):QualityCase[]{
 const file=object(value)
 if(file.schemaVersion!==1||file.sampleMode!=='synthetic'||!Array.isArray(file.reviews)||file.reviews.length!==cases.length)throw Error('人评文件必须完整对应本批场景。')
 const found=new Map<string,QualityReview>()
 for(const item of file.reviews){
  const r=object(item),c=cases.find(c=>c.id===r.scenarioId)
  if(!c||found.has(c.id)||r.inputHash!==c.inputHash||r.reportHash!==c.reportHash)throw Error('场景或报告已变化，不能套用这份人评。')
  if(!['PENDING','APPROVED'].includes(String(r.annotationStatus))||!name(r.annotationReviewer)||!name(r.qualityReviewer)||!rating(r.factsVsInferencesScore)||!(r.expressesUncertainty===null||typeof r.expressesUncertainty==='boolean')||!(r.unsafeRecommendation===null||typeof r.unsafeRecommendation==='boolean')||!strings(r.relevantEvidence)||new Set(r.relevantEvidence).size!==r.relevantEvidence.length||r.relevantEvidence.some(id=>!c.evidence.some(e=>e.id===id))||!(r.actionabilityScore===undefined||rating(r.actionabilityScore))||!(r.reviewerNotes===undefined||text(r.reviewerNotes,2000)))throw Error('人评字段无效，请检查姓名、1–5 分、证据与判断项。')
  if(r.annotationStatus==='APPROVED'&&!String(r.annotationReviewer??'').trim())throw Error('确认金标准需要填写复核人。')
  const judged=[r.factsVsInferencesScore,r.expressesUncertainty,r.unsafeRecommendation,r.actionabilityScore].some(v=>v!==null&&v!==undefined)
  if(judged&&(!c.report||!String(r.qualityReviewer??'').trim()))throw Error('报告评分需要已接受的报告和评分人。')
  found.set(c.id,structuredClone(r) as unknown as QualityReview)
 }
 return cases.map(c=>({...c,review:found.get(c.id)!}))
}
