"""Render a complete Nginx configuration; never launch or modify a deployment."""

from __future__ import annotations

from dataclasses import dataclass, fields
import json
from pathlib import Path
import re


# Current Server/Scenario operations. These are method/path allowlists, not prefix grants.
ADMIN_OPERATIONS = (
    ("GET", "/api/v1/projects"),
    ("GET", "/api/v1/projects/{projectId}"),
    ("GET", "/api/v1/projects/{projectId}/worker-groups"),
    ("GET", "/api/v1/projects/{projectId}/tasks"),
    ("GET", "/api/v1/projects/{projectId}/tasks/{taskId}"),
    ("POST", "/api/v1/projects/{projectId}/tasks:stats"),
    ("GET", "/api/v1/me"),
    ("GET", "/api/v1/users"), ("POST", "/api/v1/users"),
    ("PUT", "/api/v1/users/{userId}"), ("GET", "/api/v1/roles"),
    ("POST", "/api/v1/users/{userId}/access-key"), ("DELETE", "/api/v1/users/{userId}/access-key"),
    ("GET", "/api/v1/users/{userId}/credits"),
    ("GET", "/api/v1/users/{userId}/credits/transactions"),
    ("GET", "/api/v1/users/{userId}/credits/adjustments/{requestId}"),
    ("POST", "/api/v1/users/{userId}/credits/adjustments"),
    ("POST", "/api/v1/tasks"),
    *(("POST", "/api/v1/tasks/{taskId}/" + operation) for operation in (
        "approve", "close", "directory:ensure", "rpc:prepare", "items", "items:call",
        "items:states", "results:load", "results:take", "results:export", "retention:run")),
    ("GET", "/api/v1/tasks/{taskId}/results:preview"),
    ("POST", "/api/v1/runtime-view/tasks:preview"),
    ("POST", "/api/v1/runtime-view/worker-groups:batch-get"),
    ("POST", "/api/v1/runtime-view/worker-groups:preview"),
    ("GET", "/api/v1/runtime-view/worker-groups/bindings"),
    ("GET", "/api/v1/runtime-view/worker-groups/{workerGroupId}/bindings"),
    ("POST", "/api/v1/runtime-view/worker-groups/{workerGroupId}/workers:preview"),
    ("POST", "/api/v1/runtime-view/worker-groups/{workerGroupId}/workers:scheduling-observe"),
    ("POST", "/api/v1/runtime-view/endpoint-managers/{endpointManagerId}/workers:network-observe"),
    ("POST", "/api/v1/worker-groups/{workerGroupId}:register"),
    ("PATCH", "/api/v1/worker-groups/{workerGroupId}/workers/{workerId}/platform-properties"),
    ("POST", "/api/v1/worker-groups/{workerGroupId}/workers/{workerId}:pause-scheduling"),
    ("POST", "/api/v1/worker-groups/{workerGroupId}/workers/{workerId}:resume-scheduling"),
    ("POST", "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/direct-calls"),
    ("GET", "/api/v1/sms/catalog"), ("GET", "/api/v1/sms/metrics"),
    ("GET", "/api/v1/sms/messages/{messageId}"), ("POST", "/api/v1/sms/numbers:lease"),
    ("GET", "/api/v1/messages/catalog"), ("POST", "/api/v1/messages/tasks"),
    ("POST", "/api/v1/messages/tasks/{taskId}/recipients:import"),
    ("GET", "/api/v1/messages/tasks/{taskId}/results"),
    ("GET", "/api/v1/app-checks/catalog"), ("GET", "/api/v1/app-checks/configuration"),
    ("PUT", "/api/v1/app-checks/configuration"), ("POST", "/api/v1/app-checks/tasks"),
    ("GET", "/api/v1/app-checks/tasks/{taskId}/credits"),
    ("GET", "/api/v1/app-checks/tasks/{taskId}/results"),
    ("POST", "/api/v1/app-checks/tasks/{taskId}/seeds:import"),
    ("POST", "/api/v1/app-checks/tasks/{taskId}/results:export"),
)

_SEGMENT = "[A-Za-z0-9_.:-]+"
ADMIN_PAGE_PATTERNS = (
    r"^(GET|HEAD):/(?:index\.html)?$",
    r"^(GET|HEAD):/(?:runtime/(?:workers|tasks)|task-workbench|api-reference|reference/error-codes)/?$",
    rf"^(GET|HEAD):/(?:sms(?:/metrics)?|messages(?:/tasks/{_SEGMENT})?|app-checks(?:/tasks/{_SEGMENT})?)/?$",
    # Vite emits static/js, static/css and static/<extension>; VitePress owns docs/assets.
    r"^(GET|HEAD):/(?:static|assets|docs|reference)/[A-Za-z0-9_./-]*$",
    r"^(GET|HEAD):/(?:favicon\.ico|favicon\.svg|logo\.svg|robots\.txt)$",
    r"^(GET|HEAD):/(?:docs|scalar)/?$",
)
_HOST = re.compile(r"(?=.{1,253}\Z)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\Z")
_GROUP = re.compile(r"[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}\Z")
_PATH = re.compile(r"/[A-Za-z0-9_./-]+\Z")


def operation_pattern(method: str, path: str) -> str:
    parts = re.split(r"(\{[^}]+\})", path)
    return "^" + method + ":" + "".join(
        _SEGMENT if part.startswith("{") else re.escape(part) for part in parts
    ) + "$"


def _map_regex(pattern: str, *, ignore_case: bool = False) -> str:
    # Quote the whole map key (including ~); semicolons inside regexes must not
    # become config delimiters. Nginx unescapes double backslashes once.
    key = ("~*" if ignore_case else "~") + pattern
    return '        "' + key.replace("\\", "\\\\").replace('"', '\\"') + '" 1;'


@dataclass(frozen=True)
class Deployment:
    certificate: str
    certificate_key: str
    worker_password_file: str
    acme_root: str
    state_directory: str
    worker_groups: tuple[str, ...]
    admin_host: str = "staging.xamass.net"
    worker_host: str = "workers-staging.xamass.net"
    listen_host: str = "0.0.0.0"
    http_port: int = 80
    https_port: int = 443
    runtime_port: int = 18182
    adapter_port: int = 18183

    def __post_init__(self) -> None:
        for name in ("admin_host", "worker_host"):
            value = getattr(self, name)
            if not isinstance(value, str) or not _HOST.fullmatch(value) or ".." in value:
                raise ValueError(f"{name} must be a lowercase ASCII hostname or IPv4 address")
        if self.admin_host == self.worker_host:
            raise ValueError("admin_host and worker_host must differ")
        if self.listen_host not in ("0.0.0.0", "127.0.0.1"):
            raise ValueError("listen_host must be 0.0.0.0 or 127.0.0.1")
        ports = [getattr(self, name) for name in ("http_port", "https_port", "runtime_port", "adapter_port")]
        if any(type(port) is not int or not 1 <= port <= 65535 for port in ports) or len(set(ports)) != 4:
            raise ValueError("the four ports must be distinct integers in 1..65535")
        for name in ("certificate", "certificate_key", "worker_password_file", "acme_root", "state_directory"):
            value = getattr(self, name)
            if not isinstance(value, str) or not _PATH.fullmatch(value) or ".." in value.split("/"):
                raise ValueError(f"{name} must be an absolute POSIX path using letters, digits, _, -, . and /")
        if not isinstance(self.worker_groups, (list, tuple)) or not self.worker_groups:
            raise ValueError("worker_groups must explicitly name at least one Group")
        if any(not isinstance(group, str) or not _GROUP.fullmatch(group) for group in self.worker_groups):
            raise ValueError("worker_groups must contain safe ASCII Group IDs")
        if len(set(self.worker_groups)) != len(self.worker_groups) or len(self.worker_groups) > 100:
            raise ValueError("worker_groups must contain 1..100 distinct Group IDs")
        object.__setattr__(self, "worker_groups", tuple(sorted(self.worker_groups)))

    @classmethod
    def load(cls, path: Path) -> Deployment:
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError(f"duplicate deployment field: {key}")
                result[key] = value
            return result
        data = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
        if not isinstance(data, dict) or set(data) - {field.name for field in fields(cls)}:
            raise ValueError("deployment must be an object containing only documented fields")
        try:
            return cls(**data)
        except TypeError as failure:
            raise ValueError("deployment is missing required fields") from failure


def _proxy(port: int, websocket: bool = False, authorization: str = '""') -> str:
    # The Worker realm's Basic credential is consumed here and never forwarded. The administrator
    # host forwards only a well-formed access key, which the Runtime itself authenticates.
    return f"""proxy_pass http://127.0.0.1:{port};
            proxy_http_version 1.1;
            proxy_set_header Host $host;
            proxy_set_header Authorization {authorization};
            proxy_set_header Proxy-Authorization "";
            proxy_set_header Forwarded "";
            proxy_set_header X-Forwarded-For $remote_addr;
            proxy_set_header X-Forwarded-Proto https;
            proxy_set_header X-Forwarded-Host $host;
            proxy_set_header X-Forwarded-Port $server_port;
            proxy_set_header X-XA-Ingress-User "";
            proxy_hide_header Access-Control-Allow-Origin;
            proxy_hide_header Access-Control-Allow-Credentials;
            proxy_hide_header Access-Control-Allow-Methods;
            proxy_hide_header Access-Control-Allow-Headers;
            proxy_hide_header Access-Control-Expose-Headers;
            proxy_hide_header Access-Control-Max-Age;
            proxy_set_header Upgrade {('$http_upgrade' if websocket else '""')};
            proxy_set_header Connection {('upgrade' if websocket else '""')};
            proxy_next_upstream off;
            proxy_read_timeout {('1h' if websocket else '120s')};
            proxy_send_timeout {('1h' if websocket else '120s')};
            proxy_buffering off;"""


def render(config: Deployment) -> str:
    operations = "\n".join(_map_regex(operation_pattern(method, path)) for method, path in ADMIN_OPERATIONS)
    pages = "\n".join(_map_regex(pattern) for pattern in ADMIN_PAGE_PATTERNS)
    unsafe = _map_regex(r"^[^?]*(?:%|;|//|\\|/\.\.?(/|[?]|$))")
    bad_origin = _map_regex(r"^(POST|PUT|PATCH|DELETE):1:")
    cross_site = _map_regex(r"^(POST|PUT|PATCH|DELETE):[01]:cross-site$", ignore_case=True)
    websocket = _map_regex(r"^websocket:.*upgrade", ignore_case=True)
    worker_operations = "\n".join(
        f'        "POST:/api/v1/worker-groups/{group}/workers:{operation}" 1;'
        for group in config.worker_groups for operation in ("prepare", "prepare-batch")
    )
    origin = f"https://{config.admin_host}" + ("" if config.https_port == 443 else f":{config.https_port}")
    tls = f"""ssl_certificate {config.certificate};
        ssl_certificate_key {config.certificate_key};
        ssl_protocols TLSv1.2 TLSv1.3;"""
    return f"""# Generated by XA Mass staging_ingress. The Runtime authenticates administrator access keys;
# this gateway owns TLS, route allowlists, limits and the shared Worker Host realm.
worker_processes 1;
pid {config.state_directory}/nginx.pid;
error_log {config.state_directory}/error.log warn;
events {{ worker_connections 2048; }}
http {{
    server_tokens off;
    map_hash_bucket_size 512;
    server_names_hash_bucket_size 512;
    client_max_body_size 16m;
    client_header_timeout 15s;
    client_body_timeout 30s;
    keepalive_timeout 30s;
    client_body_temp_path {config.state_directory}/client_temp;
    proxy_temp_path {config.state_directory}/proxy_temp;
    fastcgi_temp_path {config.state_directory}/fastcgi_temp;
    uwsgi_temp_path {config.state_directory}/uwsgi_temp;
    scgi_temp_path {config.state_directory}/scgi_temp;
    log_format staging '$time_iso8601 $host $remote_user $request_method $uri $status';
    access_log {config.state_directory}/access.log staging;
    limit_req_zone $binary_remote_addr zone=admin_rate:1m rate=30r/s;
    limit_req_zone $binary_remote_addr zone=worker_rate:1m rate=10r/s;
    limit_conn_zone "$server_name:$remote_user" zone=worker_credential:1m;
    limit_conn_zone $server_name zone=worker_total:1m;
    limit_req_status 429;
    limit_conn_status 429;
    map "$request_method:$uri" $admin_route {{
        default 0;
{operations}
{pages}
        "GET:/v3/api-docs" 1;
        "GET:/v3/api-docs/swagger-config" 1;
        "GET:/actuator/health/readiness" 1;
    }}
    map "$request_method:$uri" $worker_prepare {{
        default 0;
{worker_operations}
    }}
    # Refuse encoded/ambiguous path forms before route selection; query data is unaffected.
    map $request_uri $unsafe_path {{
        default 0;
{unsafe}
    }}
    map $http_origin $bad_admin_origin {{
        default 1;
        "" 0;
        "{origin}" 0;
    }}
    map "$request_method:$bad_admin_origin:$http_sec_fetch_site" $bad_admin_mutation {{
        default 0;
{bad_origin}
{cross_site}
    }}
    map $http_authorization $admin_access_key {{
        default "";
        "~^Bearer xak_[A-Za-z0-9_-]{{8,128}}$" $http_authorization;
    }}
    map "$http_upgrade:$http_connection" $websocket_upgrade {{
        default 0;
{websocket}
    }}
    server {{ listen {config.listen_host}:{config.http_port} default_server; server_name _; return 404; }}
    server {{
        listen {config.listen_host}:{config.http_port};
        server_name {config.admin_host} {config.worker_host};
        location ~ "^/\\.well-known/acme-challenge/[A-Za-z0-9_-]+$" {{
            if ($request_method !~ ^(GET|HEAD)$) {{ return 405; }}
            root {config.acme_root};
            try_files $uri =404;
        }}
        location / {{ return 404; }}
    }}
    server {{
        listen {config.listen_host}:{config.https_port} ssl default_server;
        server_name _;
        {tls}
        return 404;
    }}
    server {{
        listen {config.listen_host}:{config.https_port} ssl;
        server_name {config.admin_host};
        {tls}
        if ($unsafe_path) {{ return 400; }}
        if ($admin_route = 0) {{ return 404; }}
        if ($bad_admin_mutation) {{ return 403; }}
        location / {{
            limit_req zone=admin_rate burst=60 nodelay;
            {_proxy(config.runtime_port, authorization="$admin_access_key")}
        }}
    }}
    server {{
        listen {config.listen_host}:{config.https_port} ssl;
        server_name {config.worker_host};
        {tls}
        auth_basic "XA Mass staging Worker Hosts";
        auth_basic_user_file {config.worker_password_file};
        if ($unsafe_path) {{ return 400; }}
        location = /api/v1/worker-delivery/websocket {{
            if ($request_method != GET) {{ return 405; }}
            if ($websocket_upgrade = 0) {{ return 400; }}
            limit_req zone=worker_rate burst=30 nodelay;
            # try_files runs after the access phase. An empty filename tests the directory
            # as a file, cannot serve it, and enters the internal named location.
            root {config.state_directory};
            try_files "" @authenticated_websocket;
        }}
        location @authenticated_websocket {{
            # Only the successful Basic-authenticated public location can enter here.
            # Do not move these limits to PREACCESS before auth_basic has verified the user.
            auth_basic off;
            limit_conn worker_credential 100;
            limit_conn worker_total 200;
            {_proxy(config.adapter_port, websocket=True)}
        }}
        location / {{
            if ($worker_prepare = 0) {{ return 404; }}
            limit_req zone=worker_rate burst=30 nodelay;
            {_proxy(config.runtime_port)}
        }}
    }}
}}
"""
