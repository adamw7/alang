function splitBill(totalCents, people) {
  const share = Math.floor(totalCents / people);
  return Array(people).fill(share);
}

console.log(splitBill(1000, 0));
