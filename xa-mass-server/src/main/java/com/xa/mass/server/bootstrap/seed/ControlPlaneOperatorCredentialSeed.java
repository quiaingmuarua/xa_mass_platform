package com.xa.mass.server.bootstrap.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.auth.operator.OperatorCredentialRecord;
import com.xa.mass.api.auth.operator.OperatorCredentialStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class ControlPlaneOperatorCredentialSeed {

    private List<CredentialSeed> operatorCredentials = new ArrayList<>();

    List<CredentialSeed> getOperatorCredentials() {
        return operatorCredentials == null ? List.of() : operatorCredentials;
    }

    public void setOperatorCredentials(List<CredentialSeed> operatorCredentials) {
        this.operatorCredentials = operatorCredentials;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    static final class CredentialSeed {
        private String userId;
        private String passwordHash;
        private String hashAlgorithm;
        private String status;
        private Instant createdAt;
        private Instant updatedAt;

        String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        String getHashAlgorithm() {
            return hashAlgorithm;
        }

        public void setHashAlgorithm(String hashAlgorithm) {
            this.hashAlgorithm = hashAlgorithm;
        }

        String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }

        OperatorCredentialRecord toRecord() {
            return new OperatorCredentialRecord(
                    userId,
                    passwordHash,
                    hashAlgorithm,
                    status == null || status.isBlank()
                            ? OperatorCredentialStatus.ACTIVE
                            : OperatorCredentialStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT)),
                    createdAt,
                    updatedAt
            );
        }
    }
}
