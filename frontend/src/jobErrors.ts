type Explanation = { title: string; message: string };
const explanations: Record<string, Explanation> = {
  PAYLOAD_EXPIRED: {
    title: "正文保留期已到",
    message: "正文已过期，不能读取、重跑或补投。任务、调用记录和原用量保留；已发送的模型请求不会自动重发。",
  },
  MODEL_CONFIG_CHANGED: {
    title: "模型配置已变更",
    message:
      "任务在发送前停止，预留额度已释放。核对当前配置后，可明确重跑创建新任务。",
  },
  MODEL_REJECTED: {
    title: "模型服务拒绝请求",
    message:
      "请管理员检查密钥、模型名、接口参数和供应商额度。调用记录与用量已保留，平台不会自动重发。",
  },
  MODEL_REFUSED: {
    title: "模型未返回可用答复",
    message: "供应商拒答或限制了此次输出。结果未保存，任务和调用记录保留。",
  },
  OUTPUT_TRUNCATED: {
    title: "模型输出未完成",
    message:
      "输出达到供应商限制，未保存不完整结果。请核对模型输出上限，确认后通过明确重跑创建新调用。",
  },
  OUTPUT_TOO_LARGE: {
    title: "模型响应超过保存上限",
    message:
      "平台未保存超出上限的响应。请调整输入或输出要求，原调用记录和用量保留。",
  },
  OUTPUT_INVALID: {
    title: "模型输出结构不符合要求",
    message:
      "响应未通过结构验证，未保存为成功结果。请检查模型兼容性；修正后可明确重跑，原用量不自动退款。",
  },
  EVIDENCE_INVALID: {
    title: "原文依据校验失败",
    message:
      "模型引用未能精确匹配原文，结果未保存。请核对输入与模型输出，原任务和调用记录保留。",
  },
  MODEL_RESULT_UNKNOWN: {
    title: "模型执行结果未知",
    message:
      "请求可能已在供应商执行，平台未取得可验证结果。不会自动重新调用；再次运行须明确确认新调用与费用。",
  },
  QUEUE_EXPIRED: {
    title: "排队时间已过期",
    message: "任务未发送给模型，预留额度已释放。确认后可创建新的关联任务。",
  },
  LOCAL_PRECHECK_FAILED: {
    title: "本地处理未完成",
    message:
      "任务在发送前停止，预留额度已释放。请管理员核对输入解密与本地配置。",
  },
};
export function jobError(code: string): Explanation {
  return (
    explanations[code] ?? {
      title: "任务未完成",
      message: "请凭错误码联系管理员核对处理记录。平台不会自动重新调用模型。",
    }
  );
}
