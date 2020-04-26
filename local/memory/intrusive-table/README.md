intrusive-table
===============

A hash (?) table that allows allocating memory within a specified memory region, while containing its own data at the beginning of that region. This is useful e.g. for allocating chunks of memory within a shared memory region that are then accessible to other processes.

# Goals
- Allocate memory very quickly (ideally just directly in a row).

# Non-goals *(for now)*
- Free memory (at all).
- Resize the table, or otherwise provide for overrunning the memory region except by panicking.

# License
[Apache v2](../../../LICENSE)
