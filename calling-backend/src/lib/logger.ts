import { appendFileSync, existsSync, mkdirSync } from "fs";
import { join } from "path";

const LOG_DIR = join(process.cwd(), "logs");
if (!existsSync(LOG_DIR)) mkdirSync(LOG_DIR, { recursive: true });

const pad = (n: number) => String(n).padStart(2, "0");

export const todayStr = () => {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

const timestamp = () => {
  const d = new Date();
  return `${todayStr()} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
};

const write = (level: string, msg: string) => {
  const line = `[${timestamp()}] ${level} | ${msg}`;
  console.log(line);
  try {
    appendFileSync(join(LOG_DIR, `calling-${todayStr()}.log`), line + "\n", "utf8");
  } catch { /* non-fatal */ }
};

export const logger = {
  info:  (msg: string) => write("INFO ", msg),
  warn:  (msg: string) => write("WARN ", msg),
  error: (msg: string) => write("ERROR", msg),

  logDir: LOG_DIR,
};
