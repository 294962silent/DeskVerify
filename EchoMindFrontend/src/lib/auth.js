export const ACCESS_TOKEN_KEY = 'echomind_access_token'

export function getAccessToken(storage = globalThis.sessionStorage) {
  const token = storage?.getItem(ACCESS_TOKEN_KEY)?.trim()
  return token || ''
}

export function saveAccessToken(token, storage = globalThis.sessionStorage) {
  const value = String(token || '').trim()
  if (value) {
    storage?.setItem(ACCESS_TOKEN_KEY, value)
  } else {
    storage?.removeItem(ACCESS_TOKEN_KEY)
  }
}

export function javaAuthHeaders(type, storage = globalThis.sessionStorage) {
  const token = getAccessToken(storage)
  if (!token) {
    throw new Error('Java backend requires an access token for this session.')
  }
  return { Authorization: `Bearer ${token}` }
}

export function withAuthorization(type, options = {}, storage = globalThis.sessionStorage) {
  return {
    ...options,
    headers: {
      ...(options.headers || {}),
      ...javaAuthHeaders(type, storage)
    }
  }
}

export async function authorizedFetch(
  url,
  type,
  options = {},
  storage = globalThis.sessionStorage,
  fetchImpl = globalThis.fetch
) {
  return fetchImpl(url, withAuthorization(type, options, storage))
}
