export class ApiError extends Error {
  constructor(
    public code: string,
    public status: number,
  ) {
    super(code);
  }
}
export async function request<T>(
  path: string,
  signal?: AbortSignal,
  init?: RequestInit,
): Promise<T> {
  const response = await fetch(
    new URL(`api/v1/${path}`, new URL("./", document.baseURI)),
    {
      credentials: "same-origin",
      signal: AbortSignal.any([
        ...(signal ? [signal] : []),
        AbortSignal.timeout(15000),
      ]),
      ...init,
    },
  );
  if (!response.ok) {
    let code = "REQUEST_FAILED";
    try {
      code = (await response.json()).error;
    } catch {
      /* A failed proxy may return HTML. */
    }
    throw new ApiError(code, response.status);
  }
  return response.json() as Promise<T>;
}
