package com.xa.mass.client.task;

import java.util.List;

public record TaskResultReadRequest(Long afterSeq, Integer limit) {
    public static Builder builder() {
        return new Builder();
    }

    String toQueryString() {
        return TaskClient.query(List.of(
                new TaskClient.QueryParam("afterSeq", afterSeq == null ? null : afterSeq.toString()),
                new TaskClient.QueryParam("limit", limit == null ? null : limit.toString())
        ));
    }

    public static final class Builder {
        private Long afterSeq;
        private Integer limit;

        private Builder() {
        }

        public Builder afterSeq(Long afterSeq) {
            this.afterSeq = afterSeq;
            return this;
        }

        public Builder limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        public TaskResultReadRequest build() {
            return new TaskResultReadRequest(afterSeq, limit);
        }
    }
}
