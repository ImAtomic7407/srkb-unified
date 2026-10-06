import type { Response } from "express";

export const sendCsv = (res: Response, filename: string, csv: string) => {
  res.status(200);
  res.setHeader("Content-Type", "text/csv; charset=utf-8");
  res.setHeader("Content-Disposition", `attachment; filename="${filename}"`);
  res.send(csv);
};
