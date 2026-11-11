#include <stdio.h>

int midpoint(int lo, int hi) {
    return (lo + hi) / 2;
}

int main(void) {
    printf("%d\n", midpoint(2000000000, 2100000000));
    return 0;
}
