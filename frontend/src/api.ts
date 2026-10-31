import { reactive } from 'vue'
import type { User } from './types'

// Session tokens deliberately live only in memory. A reload returns to login.
export const auth = reactive({ token: '', expiresAt: '', user: null as User | null, reason: '' })
export const hasRole = (role: string) => auth.user?.roles.includes(role) ?? false
export function clearSession(reason = '') { auth.token = ''; auth.expiresAt = ''; auth.user = null; auth.reason = reason }
const messages: Record<string, string> = {
  KUMA_DISABLED:'后端尚未启用 Kuma，请由部署管理员配置 KUMA_ENABLED 和服务地址后重启后端。',
  KUMA_SMOKE_REQUIRED:'请先启用后端 Kuma 配置，并完成一次成功的测试推送。',KUMA_ORIGIN_BLOCKED:'此地址不在后端允许范围内，请核对部署配置；不能粘贴含 token 的 Push URL。',
  KUMA_SECRET_REFERENCE_INVALID:'请填写 env:KUMA_PUSH_TOKEN_ 开头的环境变量引用。',KUMA_MAPPING_EXISTS:'此目标已有 Kuma 配置，请刷新查看。',KUMA_VERSION_CONFLICT:'配置已变化，请刷新后重新操作。',
  AGENT_MODEL_OUTCOME_UNKNOWN:'模型调用是否完成暂时无法确认，为避免重复调用，此任务不能重试。请先核对服务商调用记录；需要新诊断时，从事故详情明确发起。',
  MODEL_CONFIGURATION_REQUIRED:'请先在“AI 故障诊断 → 模型配置与用量”中保存 DeepSeek API Key。',MODEL_CONFIGURATION_VERSION_CONFLICT:'模型配置已被其他管理员更新，请刷新后重新操作。',MODEL_CONFIGURATION_UNAVAILABLE:'模型配置暂时不可用，请稍后重试。',MODEL_CREDENTIAL_DECRYPTION_FAILED:'已保存的模型密钥无法解密，请重新保存。',
  MODEL_AUTHENTICATION_FAILED:'DeepSeek 拒绝了该 API Key，请核对后重试。',MODEL_CREDENTIAL_INVALID:'API Key 格式不正确。',MODEL_RATE_LIMITED:'DeepSeek 请求频率受限，请稍后重试。',MODEL_TIMEOUT:'连接 DeepSeek 超时，请检查网络后重试。',MODEL_TLS_VERIFY_FAILED:'DeepSeek TLS 证书校验失败；系统不会绕过证书校验。',MODEL_REQUEST_REJECTED:'DeepSeek 拒绝了请求，请检查账户与模型权限。',MODEL_RESPONSE_INVALID:'DeepSeek 返回的数据无法验证。',MODEL_RESPONSE_TOO_LARGE:'DeepSeek 返回内容超过安全上限。',MODEL_REQUIRED_MODEL_UNAVAILABLE:'当前账户无法使用 deepseek-flash。',MODEL_PROVIDER_UNAVAILABLE:'DeepSeek 服务暂时不可用，请稍后重试。',MODEL_USAGE_RANGE_INVALID:'请选择 31 天以内的有效时间范围。',
  APPROVAL_VERSION_CONFLICT:'审阅版本已变化，请刷新最新报告后重新核对。',APPROVAL_ALREADY_DECIDED:'此任务已经审阅或取消，请刷新查看结果。',APPROVAL_EXPIRED:'已超过24小时审阅时限，请从事故详情发起新任务。',REVIEW_NOT_AVAILABLE:'当前任务不能审阅，请刷新状态。',MANUAL_REPORT_NOT_AVAILABLE:'只有已批准且同步完成的任务可以回填一次线下结果。',
  RUNBOOK_VERSION_CONFLICT:'手册已被更新，请刷新最新内容后重新编辑。', RUNBOOK_SECTION_LIMIT:'正文最多 5 个标题段落，每段不超过 6,000 字。', RUNBOOK_DISABLED:'手册已停用，请先启用再发布。', RUNBOOK_ALREADY_PUBLISHED:'当前内容已经发布。',AGENT_NOT_RETRYABLE:'任务状态已变化，只有 DEAD 任务可明确重试。',AGENT_SCHEMA_INVALID:'诊断结果未通过结构或引用校验，请联系管理员。',
  INVALID_CREDENTIALS: '用户名或密码不正确，请重新输入。', UNAUTHENTICATED: '登录已过期，请重新登录。',
  FORBIDDEN: '当前账号没有执行此操作的权限。', RESOURCE_NOT_FOUND: '资源不存在，或你尚未获得访问权限。',
  VALIDATION_FAILED: '请检查表单中的格式、必填项和长度。', TARGET_URL_BLOCKED: '目标地址未通过网络策略校验，请检查域名、协议与解析地址。',
  TARGET_NETWORK_NOT_ALLOWED: '该私网 CIDR 超出部署允许的出站网段，请联系管理员调整部署策略。', INVALID_PRIVATE_CIDR: '请输入有效的私网 CIDR，例如 10.20.1.0/24。',
  ACTIVE_INCIDENT_CONFIG_FROZEN: '目标存在活动事故，检查和网络配置已冻结。请先处理事故。', NETWORK_APPROVAL_REASON_REQUIRED: '启用 HTTP 或私网访问需要填写批准理由。',
  CHECK_VERSION_CONFLICT: '检查已被更新，请刷新后重新操作。', TARGET_VERSION_CONFLICT: '目标配置已被更新，请刷新后重新编辑。',
  INCIDENT_VERSION_CONFLICT: '事故状态已变化，请刷新时间线后重试。', INVALID_INCIDENT_TRANSITION: '当前状态不能执行此操作，解决事故前需要检查恢复证据。',
  INVALID_CHECK_TRANSITION: '检查状态已变化，请刷新后重试。', LAST_ADMIN_REQUIRED: '至少需要保留一位活跃管理员。',
  INVALID_PASSWORD_LENGTH: '密码至少 12 个字符，且不能超过 72 个 UTF-8 字节。', IDEMPOTENCY_CONFLICT: '提交内容与此前请求不一致，请重新编辑并提交。',
  RESOURCE_CONFLICT: '相同标识已存在，请更换标识后提交。', DEPENDENCY_UNAVAILABLE: '服务暂时不可用，请稍后重试。',
}
export class ApiProblem extends Error {
  constructor(public code: string, public status: number, public traceId = '') { super(messages[code] || '请求未完成，请重试；仍失败时可使用请求编号排查。') }
}
interface Options { method?: string; body?: unknown; key?: string }
export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const usedToken = auth.token, controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 15000)
  try {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (usedToken && path !== '/auth/login') headers.Authorization = `Bearer ${usedToken}`
    if (options.key) headers['Idempotency-Key'] = options.key
    const response = await fetch(`/api/v1${path}`, { method: options.method || 'GET', headers, body: options.body === undefined ? undefined : JSON.stringify(options.body), signal: controller.signal, cache: 'no-store' })
    if (!response.ok) {
      const problem = await response.json().catch(() => ({}))
      if (response.status === 401 && path !== '/auth/login' && auth.token === usedToken) clearSession('登录已过期，请重新登录。')
      throw new ApiProblem(problem.code || 'REQUEST_FAILED', response.status, problem.traceId)
    }
    return response.status === 204 ? undefined as T : await response.json() as T
  } catch (e) {
    if (e instanceof ApiProblem) throw e
    throw new Error('无法连接服务，请检查后端是否运行，然后重试。')
  } finally { clearTimeout(timer) }
}
export async function refreshIdentity() { auth.user = await api<User>('/me') }
export async function login(username: string, password: string) {
  const response = await api<{ token: string; expiresAt: string }>('/auth/login', { method: 'POST', body: { username, password } })
  auth.token = response.token; auth.expiresAt = response.expiresAt; auth.reason = ''
  try { await refreshIdentity() } catch (e) { clearSession(); throw e }
}
export async function logout() { await api<void>('/auth/logout', { method: 'POST' }); clearSession() }
export function errorText(e: unknown) { return e instanceof Error ? e.message : '请求未完成，请重试。' }
