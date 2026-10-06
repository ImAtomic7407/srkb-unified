import dotenv from "dotenv";

dotenv.config();

const required = (key: string, fallback?: string) => {
  const value = process.env[key] ?? fallback;
  if (!value) {
    throw new Error(`Missing environment variable: ${key}`);
  }
  return value;
};

export const env = {
  port: Number(process.env.PORT ?? 3000),
  databaseUrl: required("DATABASE_URL", "file:./dev.db"),
  nodeEnv: process.env.NODE_ENV ?? "development",
  jwtSecret: required("JWT_SECRET"),
  jwtExpiresIn: process.env.JWT_EXPIRES_IN ?? "7d",
  // Unified auth — shared with AttendEase; used to sign and verify all JWTs
  jwtSharedSecret: required("JWT_SHARED_SECRET"),
  // Path to AttendEase's SQLite database (read-only cross-system queries)
  attendeaseDbPath: process.env.ATTENDEASE_DB_PATH ?? "",
  // Shared secret for internal webhook from AttendEase → this server
  internalToken: required("INTERNAL_TOKEN"),
  // Bootstrap host account (used by scripts/bootstrap-host.ts only)
  bootstrapHostUsername: process.env.BOOTSTRAP_HOST_USERNAME ?? "",
  bootstrapHostPassword: process.env.BOOTSTRAP_HOST_PASSWORD ?? "",
};

process.env.DATABASE_URL = env.databaseUrl;
