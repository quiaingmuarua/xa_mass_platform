import { simulationSchema, type CheckDetail, type CheckResult } from "./model";

export interface Verification {
  status: "matched" | "mismatch" | "unavailable";
  reason: string;
  bucket?: number;
  expectedDelay?: number;
}
export const verificationLabels = {
  matched: "复算一致",
  mismatch: "不一致",
  unavailable: "无法核对"
};
export const cryptoAvailable = () =>
  typeof crypto !== "undefined" && typeof crypto.subtle?.digest === "function";

export async function hashValue(
  domain: "outcome" | "delay",
  workerId: string,
  salt: string,
  number: string
): Promise<bigint> {
  const fields = [`app-checks/v1/${domain}`, workerId, salt, number].map((value) =>
    new TextEncoder().encode(value)
  );
  const tuple = new Uint8Array(
    fields.reduce((sum, field) => sum + 4 + field.length, 0)
  );
  const view = new DataView(tuple.buffer);
  let offset = 0;
  for (const field of fields) {
    view.setUint32(offset, field.length, false);
    tuple.set(field, offset + 4);
    offset += 4 + field.length;
  }
  return new DataView(await crypto.subtle.digest("SHA-256", tuple)).getBigUint64(
    0,
    false
  );
}
async function verifyResult(
  task: CheckDetail["task"],
  result: CheckResult
): Promise<Verification> {
  const simulation = simulationSchema.safeParse(task.simulation);
  if (result.resultStatus === "failed")
    return { status: "unavailable", reason: "失败记录没有原执行者，无法复算异常原因" };
  if (
    task.configurationError ||
    result.contentError ||
    !simulation.success ||
    !task.salt ||
    !task.workerGroupId ||
    !result.number ||
    !result.workerId ||
    !result.workerGroupId ||
    typeof result.registered !== "boolean" ||
    result.simulatedDelayMillis === undefined
  )
    return { status: "unavailable", reason: "缺少有效配置、关联或业务结果" };
  const bucket = Number(
    (await hashValue("outcome", result.workerId, task.salt, result.number)) % 1000n
  );
  const [min, max] = simulation.data.delayMs;
  const expectedDelay =
    min +
    Number(
      (await hashValue("delay", result.workerId, task.salt, result.number)) %
        BigInt(max - min + 1)
    );
  const outcome = Object.entries(simulation.data.ranges).find(
    ([, [a, b]]) => a <= bucket && bucket < b
  )?.[0];
  const problems = [];
  if (result.workerGroupId !== task.workerGroupId) problems.push("执行 Group 不符");
  if (outcome === "failed") problems.push("成功内容命中失败区间");
  else if (result.registered !== (outcome === "registered"))
    problems.push("注册答案不符");
  if (expectedDelay !== result.simulatedDelayMillis) problems.push("模拟延迟不符");
  return {
    status: problems.length ? "mismatch" : "matched",
    reason: problems.length ? problems.join("；") : "注册答案、Group 与模拟延迟一致",
    bucket,
    expectedDelay
  };
}
/** Verifies only this snapshot; no API, platform mutation or attempt-count inference. */
export async function verifyPreview(
  detail: CheckDetail
): Promise<Map<string, Verification>> {
  if (!cryptoAvailable())
    throw new Error("当前浏览器不支持 Web Crypto，请使用 HTTPS 或本机地址进行核对。");
  const result = new Map<string, Verification>();
  for (const row of detail.results.slice(0, 100))
    result.set(row.messageId, await verifyResult(detail.task, row));
  return result;
}
