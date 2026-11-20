import { BASE_PATH } from './config.js';

const SESSION_KEY = 'chatry.session';

export function getSession() {
  try {
    return JSON.parse(localStorage.getItem(SESSION_KEY));
  } catch {
    return null;
  }
}

export function setSession(session) {
  localStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearSession() {
  localStorage.removeItem(SESSION_KEY);
}

export function authToken() {
  return getSession()?.token || '';
}

export async function api(path, { method = 'GET', body, token = authToken() } = {}) {
  const headers = {};
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const response = await fetch(`${BASE_PATH}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 204) {
    return null;
  }
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (!response.ok) {
    const error = new Error(data?.message || `请求失败（HTTP ${response.status}）`);
    error.status = response.status;
    error.category = data?.category;
    throw error;
  }
  return data;
}

/**
 * 流式对话：解析后端 SSE（event: delta / done / error）。
 * 返回 done 事件载荷；错误抛出带 category 的 Error。
 */
export async function streamChat({ conversationId, content, onDelta, signal }) {
  const response = await fetch(`${BASE_PATH}/api/chat/conversations/${conversationId}/messages/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${authToken()}`,
    },
    body: JSON.stringify({ content }),
    signal,
  });
  if (!response.ok) {
    const text = await response.text();
    let message = `请求失败（HTTP ${response.status}）`;
    try {
      message = JSON.parse(text)?.message || message;
    } catch {
      // 非 JSON 错误体，保留默认提示
    }
    throw new Error(message);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let donePayload = null;
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let index;
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, index);
      buffer = buffer.slice(index + 2);
      const event = /(?:^|\n)event:(.*)/.exec(raw)?.[1]?.trim() || 'message';
      const dataLine = /(?:^|\n)data:(.*)/.exec(raw)?.[1] ?? '';
      let data = null;
      if (dataLine) {
        try {
          data = JSON.parse(dataLine);
        } catch {
          data = null;
        }
      }
      if (event === 'delta' && data?.delta) {
        onDelta(data.delta);
      } else if (event === 'done') {
        donePayload = data;
      } else if (event === 'error') {
        const error = new Error(data?.message || '对话生成失败');
        error.category = data?.category;
        throw error;
      }
    }
  }
  return donePayload;
}
