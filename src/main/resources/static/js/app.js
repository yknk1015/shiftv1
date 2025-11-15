(() => {
  const LOGIN_URL = '/login';
  const originalFetch = window.fetch.bind(window);
  let redirecting = false;

  function normalizeHeaders(headers = {}) {
    if (headers instanceof Headers) {
      return headers;
    }
    if (Array.isArray(headers)) {
      return new Headers(headers);
    }
    return new Headers(Object.entries(headers));
  }

  function applyDefaults(options = {}) {
    const normalized = { ...options };
    const headers = normalizeHeaders(normalized.headers || {});
    if (
      normalized.body &&
      !(normalized.body instanceof FormData) &&
      !headers.has('Content-Type')
    ) {
      headers.set('Content-Type', 'application/json');
    }
    normalized.headers = headers;
    normalized.credentials = normalized.credentials || 'include';
    return normalized;
  }

  function shouldSkipRedirect(input, options) {
    if (options && options.skipAuthRedirect) {
      return true;
    }
    const target =
      typeof input === 'string'
        ? input
        : (input && typeof input.url === 'string' ? input.url : '');
    return target.includes('/api/auth/login');
  }

  async function handleUnauthorized(response, input, options) {
    if (response.status !== 401 || redirecting || shouldSkipRedirect(input, options)) {
      return;
    }
    redirecting = true;
    const params = new URLSearchParams({
      message: 'session-expired'
    });
    window.location.href = `${LOGIN_URL}?${params.toString()}`;
  }

  async function apiFetch(input, options = {}) {
    const { timeout, ...rest } = options;
    const controller = timeout ? new AbortController() : null;
    const opts = applyDefaults(rest);
    if (controller) {
      opts.signal = controller.signal;
      setTimeout(() => {
        if (!controller.signal.aborted) {
          controller.abort();
        }
      }, timeout);
    }
    try {
      const response = await originalFetch(input, opts);
      await handleUnauthorized(response, input, options);
      return response;
    } catch (err) {
      if (err.name === 'AbortError') {
        throw new Error('リクエストがタイムアウトしました');
      }
      throw err;
    }
  }

  async function apiClient(url, options = {}) {
    const { parseJson = true, ...rest } = options;
    const response = await apiFetch(url, rest);
    if (!parseJson) {
      return response;
    }
    const data = await response.json().catch(() => ({}));
    if (!response.ok || data.success === false) {
      const message = data.message || `HTTP ${response.status}`;
      throw new Error(message);
    }
    return data;
  }

  window.nativeFetch = originalFetch;
  window.apiFetch = apiFetch;
  window.apiClient = apiClient;
  window.fetch = apiFetch;
})();
