#include <stdio.h>
#include <string.h>

struct user { char name[8]; int is_admin; };

int main(int argc, char **argv) {
    struct user u = { "", 0 };
    strcpy(u.name, argv[1]);
    printf("admin: %s\n", u.is_admin ? "yes" : "no");
    return 0;
}
