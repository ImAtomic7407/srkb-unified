import { AsyncLocalStorage } from "async_hooks";

export type RequestContext = {
  userId: number;
  centreId: number | null;   // null only on host stats endpoints (bypass)
  permissions: Set<string>;
  isAdminAtCentre: boolean;
  isHost: boolean;
};

export const requestContextStorage = new AsyncLocalStorage<RequestContext>();

export const getRequestContext = (): RequestContext => {
  const ctx = requestContextStorage.getStore();
  if (!ctx) throw new Error("No request context — called outside of a request");
  return ctx;
};
