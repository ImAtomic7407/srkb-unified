// HTTP client for AttendEase → Calling Backend internal queries.
// Used by reconcile and lazy-create when ATTENDEASE_DB_PATH is unavailable.
// All requests carry x-internal-token; failures are non-fatal (caller logs + skips).

import { env } from "../config/env.js";

const base = () => process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";
const headers = () => ({ "x-internal-token": env.internalToken, "Content-Type": "application/json" });

async function get<T>(path: string): Promise<T | null> {
  try {
    const res = await fetch(`${base()}${path}`, { headers: headers(), signal: AbortSignal.timeout(5000) });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

export type AEHttpBatch = {
  id: number;
  name: string;
  centre_id: string;
  category: string;
  is_default: boolean;
  status: string;
};

export type AEHttpSession = {
  session_id: string;
  display_name: string;
  batch_id: number;
  centre_id: string;
  category: string;
  date: string | null;
  status: string;
};

export type AEHttpCentre = {
  centre_id: string;
  name: string;
  status: string;
};

export const attendeaseHttp = {
  getBatches: (centreId: string) =>
    get<AEHttpBatch[]>(`/api/batches?centre_id=${encodeURIComponent(centreId)}`),

  getSessions: (centreId: string) =>
    get<AEHttpSession[]>(`/api/list_sessions?centre_id=${encodeURIComponent(centreId)}`),

  getCentres: () =>
    get<AEHttpCentre[]>(`/api/centres`),
};
