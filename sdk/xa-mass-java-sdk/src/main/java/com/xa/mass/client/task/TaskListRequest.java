package com.xa.mass.client.task;

import java.util.List;

public record TaskListRequest(String keyword, String project, String status, Integer offset, Integer limit) {
    public static Builder builder() {
        return new Builder();
    }

    String toQueryString() {
        return TaskClient.query(List.of(
                new TaskClient.QueryParam("keyword", keyword),
                new TaskClient.QueryParam("project", project),
                new TaskClient.QueryParam("status", status),
                new TaskClient.QueryParam("offset", offset == null ? null : offset.toString()),
                new TaskClient.QueryParam("limit", limit == null ? null : limit.toString())
        ));
    }

    public static final class Builder {
        private String keyword;
        private String project;
        private String status;
        private Integer offset;
        private Integer limit;

        private Builder() {
        }

        public Builder keyword(String keyword) {
            this.keyword = keyword;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder offset(Integer offset) {
            this.offset = offset;
            return this;
        }

        public Builder limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        public TaskListRequest build() {
            return new TaskListRequest(keyword, project, status, offset, limit);
        }
    }
}
