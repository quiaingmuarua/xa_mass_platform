export function sendResultLabel(status: string): string {
  if (["SENT", "DELIVERED", "READ", "REPLIED"].includes(status)) return "成功";
  return status === "EXECUTION_FAILED" ? "失败" : "未观察";
}

export function receiptLabel(status: string): string {
  return (
    (
      { DELIVERED: "已送达", READ: "已读", REPLIED: "已回复" } as Record<string, string>
    )[status] ?? "尚未观察"
  );
}
