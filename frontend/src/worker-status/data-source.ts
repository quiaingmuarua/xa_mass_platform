import { MockWorkerStatusDataSource } from "./mock-data-source";
import type { WorkerStatusDataSource } from "./types";

export function createWorkerStatusDataSource(): WorkerStatusDataSource {
  return new MockWorkerStatusDataSource();
}
