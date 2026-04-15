package com.xa.mass.gateway.session;

import java.util.Objects;

/**
 * 表示一个 Worker 连接的唯一标识：workerId + connRole
 */
public class WorkerConnKey {
    private final String workerId;
    private final String connRole;

    public WorkerConnKey(String workerId, String connRole) {
        this.workerId = workerId;
        this.connRole = connRole;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getConnRole() {
        return connRole;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkerConnKey)) return false;
        WorkerConnKey that = (WorkerConnKey) o;
        return Objects.equals(workerId, that.workerId) &&
                Objects.equals(connRole, that.connRole);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workerId, connRole);
    }

    @Override
    public String toString() {
        return "WorkerConnKey{" +
                "workerId='" + workerId + '\'' +
                ", connRole='" + connRole + '\'' +
                '}';
    }
}
