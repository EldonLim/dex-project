import { isError } from "ethers";

export function friendlyError(error: unknown): { rejected: boolean; message: string } {
  const candidate = error as { code?: string | number; shortMessage?: string; reason?: string; message?: string };
  const rejected = candidate?.code === 4001 || candidate?.code === "ACTION_REJECTED" || isError(error, "ACTION_REJECTED");
  if (rejected) return { rejected: true, message: "Request rejected in the wallet. Nothing was submitted." };

  const raw = candidate?.shortMessage || candidate?.reason || candidate?.message || "The transaction failed.";
  if (/insufficient funds/i.test(raw)) return { rejected: false, message: "Insufficient native currency for gas." };
  if (/user rejected/i.test(raw)) return { rejected: true, message: "Request rejected in the wallet. Nothing was submitted." };
  if (/allowance/i.test(raw)) return { rejected: false, message: "Token allowance is insufficient. Approve the required token and try again." };
  if (/balance/i.test(raw)) return { rejected: false, message: "Token balance is insufficient for this action." };
  return { rejected: false, message: raw.replace(/^execution reverted:\s*/i, "Transaction reverted: ") };
}
