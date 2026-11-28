/**
 * 与后端 common-core 中的 Result / PageResult 保持一致。
 * 后续接口返回类型在此基础上扩展，不在骨架阶段定义业务模型。
 */
export const RESULT_SUCCESS_CODE = 0

/**
 * 与后端 common-core 的 ErrorCode 枚举、docs/contracts/api-conventions.md 错误码表保持一致。
 * 成功码见 RESULT_SUCCESS_CODE；新增通用错误码时需同步此镜像。
 */
export const ERROR_CODES = {
  BAD_REQUEST: 40000,
  UNAUTHORIZED: 40100,
  FORBIDDEN: 40300,
  NOT_FOUND: 40400,
  METHOD_NOT_ALLOWED: 40500,
  CONFLICT: 40900,
  PAYLOAD_TOO_LARGE: 41300,
  TOO_MANY_REQUESTS: 42900,
  INTERNAL_ERROR: 50000,
  SERVICE_UNAVAILABLE: 50300,
} as const

export type ErrorCode = (typeof ERROR_CODES)[keyof typeof ERROR_CODES]

export interface Result<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}
