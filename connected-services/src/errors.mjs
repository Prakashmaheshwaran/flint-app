export class ApiError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}

export const fail = (status, code, message) => { throw new ApiError(status, code, message); };

export function objectBody(body, allowed) {
  if (!body || typeof body !== 'object' || Array.isArray(body) || Object.keys(body).some(key => !allowed.includes(key))) {
    fail(422, 'invalid_payload', 'The request contains unsupported fields.');
  }
  return body;
}
