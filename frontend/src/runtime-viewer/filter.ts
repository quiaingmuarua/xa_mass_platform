import type { WorkerView } from "./types";

export function filterCurrentSample(
  workers: WorkerView[],
  searchText: string
): WorkerView[] {
  const needle = searchText.trim().toLocaleLowerCase();
  const sorted = [...workers].sort((left, right) =>
    left.workerId.localeCompare(right.workerId)
  );
  if (needle === "") {
    return sorted;
  }
  return sorted.filter((worker) => searchableText(worker).includes(needle));
}

function searchableText(worker: WorkerView): string {
  return [
    worker.workerId,
    worker.workerGroupId,
    worker.endpointManagerId,
    JSON.stringify(worker.workerProperties),
    JSON.stringify(worker.platformProperties)
  ]
    .join(" ")
    .toLocaleLowerCase();
}
