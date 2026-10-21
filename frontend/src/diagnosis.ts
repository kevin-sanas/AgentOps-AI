export const agentStatus:Record<string,string>={QUEUED:'排队中',RUNNING:'诊断中',WAITING_HUMAN:'等待人工审阅',APPROVED_FOR_MANUAL:'待线下处理',REJECTED:'已拒绝',COMPLETED:'已完成',FAILED:'失败，需处理',CANCELLED:'已取消'}
export const nodeNames:Record<string,string>={validate:'校验输入',context:'整理观测',retrieval:'选取手册',analyze:'形成假设',guard:'核验引用',finalize:'确认报告'}
