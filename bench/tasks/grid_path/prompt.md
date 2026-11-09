`grid` is a maze: a list of rows of equal length, made of `.` (open) and `#` (wall). Return the
number of moves on the shortest path from the top-left cell to the bottom-right cell, moving one cell
up, down, left or right at a time through open cells only.

Return 0 when the start is the goal (a 1x1 open grid), and -1 when there is no path or when the start
or the goal is a wall.

The grid has 1 to 8 rows and 1 to 8 columns.
