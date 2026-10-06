export const normalizeMobile = (value: string) => {
  let digits = value.replace(/[^\d]/g, "");

  while (digits.startsWith("91") && digits.length > 10) {
    digits = digits.slice(2);
  }

  while (digits.startsWith("0") && digits.length > 10) {
    digits = digits.slice(1);
  }

  if (digits.length < 10 || digits.length > 15) {
    throw new Error("Mobile number must contain 10 to 15 digits");
  }

  return digits;
};
