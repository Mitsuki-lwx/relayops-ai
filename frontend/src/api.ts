let csrf: { token: string; headerName: string } | undefined;

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public traceId?: string,
  ) {
    super(message);
  }
}

export async function refreshCsrf() {
  const response = await fetch("/auth/csrf", {
    credentials: "same-origin",
    cache: "no-store",
  });
  if (!response.ok) throw new Error("无法获取安全令牌，请检查后端是否启动");
  csrf = await response.json();
}

export async function api<T>(url: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.method && !["GET", "HEAD"].includes(init.method)) {
    if (!csrf) await refreshCsrf();
    headers.set(csrf!.headerName, csrf!.token);
  }
  const response = await fetch(url, {
    ...init,
    headers,
    credentials: "same-origin",
    cache: "no-store",
  });
  if (!response.ok) {
    const error = await response
      .json()
      .catch(() => ({ message: "服务暂不可用", code: "NETWORK_ERROR" }));
    if (response.status === 401 || response.status === 403) csrf = undefined;
    throw new ApiError(
      response.status,
      error.code,
      error.message,
      error.traceId,
    );
  }
  return response.status === 204 ? (undefined as T) : await response.json();
}
