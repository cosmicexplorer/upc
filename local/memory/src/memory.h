/* Generated with cbindgen:0.8.7 */

#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

#define DEFAULT_SHM_REGION_SIZE 2000000

#define IPC_CREAT 512

#define IPC_EXCL 1024

#define IPC_M 4096

#define IPC_NOWAIT 2048

#define IPC_R 256

#define IPC_RMID 0

#define IPC_SET 1

#define IPC_STAT 2

#define IPC_W 128

#define O_ACCMODE 3

#define O_ALERT 536870912

#define O_APPEND 8

#define O_ASYNC 64

#define O_CLOEXEC 16777216

#define O_CREAT 512

#define O_DIRECTORY 1048576

#define O_DP_GETRAWENCRYPTED 1

#define O_DP_GETRAWUNENCRYPTED 2

#define O_DSYNC 4194304

#define O_EVTONLY 32768

#define O_EXCL 2048

#define O_EXLOCK 32

#define O_FSYNC 128

#define O_NDELAY 4

#define O_NOCTTY 131072

#define O_NOFOLLOW 256

#define O_NONBLOCK 4

#define O_POPUP 2147483648

#define O_RDONLY 0

#define O_RDWR 2

#define O_SHLOCK 16

#define O_SYMLINK 2097152

#define O_SYNC 128

#define O_TRUNC 1024

#define O_WRONLY 1

#define SHMLBA 4096

#define SHM_R 256

#define SHM_RDONLY 4096

#define SHM_RND 8192

#define SHM_W 128

#define S_IEXEC 64

#define S_IFBLK 24576

#define S_IFCHR 8192

#define S_IFDIR 16384

#define S_IFIFO 4096

#define S_IFLNK 40960

#define S_IFMT 61440

#define S_IFREG 32768

#define S_IFSOCK 49152

#define S_IFWHT 57344

#define S_IREAD 256

#define S_IRGRP 32

#define S_IROTH 4

#define S_IRUSR 256

#define S_IRWXG 56

#define S_IRWXO 7

#define S_IRWXU 448

#define S_ISGID 1024

#define S_ISTXT 512

#define S_ISUID 2048

#define S_ISVTX 512

#define S_IWGRP 16

#define S_IWOTH 2

#define S_IWRITE 128

#define S_IWUSR 128

#define S_IXGRP 8

#define S_IXOTH 1

#define S_IXUSR 64

typedef enum {
  AllocationSucceeded,
  DigestDidNotMatch,
  AllocationFailed,
} ShmAllocateResultStatus;

typedef enum {
  ShmCheckAllocationExists,
  ShmCheckAllocationDoesNotExist,
  ShmCheckAllocationOtherError,
} ShmCheckExistsResult;

typedef enum {
  DeletionSucceeded,
  DeleteDidNotExist,
  DeleteInternalError,
} ShmDeleteResultStatus;

typedef enum {
  RetrieveSucceeded,
  RetrieveDidNotExist,
  RetrieveInternalError,
} ShmRetrieveResultStatus;

typedef uint64_t SizeType;

typedef struct {
  SizeType size_bytes;
  Fingerprint fingerprint;
} ShmKey;

typedef struct {
  ShmKey key;
  const void *source;
} ShmAllocateRequest;

typedef struct {
  ShmKey key;
  const void *address;
  char *error_message;
  ShmAllocateResultStatus status;
} ShmAllocateResult;

typedef struct {
  const void *source;
  uint64_t size_bytes;
} ShmCheckExistsRequest;

typedef struct {
  ShmKey key;
} ShmDeleteRequest;

typedef struct {
  ShmKey key;
  char *error_message;
  ShmDeleteResultStatus status;
} ShmDeleteResult;

typedef struct {
  SizeType size;
  const void *source;
} ShmGetKeyRequest;

typedef struct {
  ShmKey key;
} ShmRetrieveRequest;

typedef struct {
  ShmKey key;
  const void *address;
  char *error_message;
  ShmRetrieveResultStatus status;
} ShmRetrieveResult;

typedef unsigned int __uint32_t;

typedef __uint32_t __darwin_uid_t;

typedef __darwin_uid_t uid_t;

typedef __uint32_t __darwin_gid_t;

typedef __darwin_gid_t gid_t;

typedef unsigned short __uint16_t;

typedef __uint16_t __darwin_mode_t;

typedef __darwin_mode_t mode_t;

typedef int __int32_t;

typedef __int32_t key_t;

typedef struct {
  uid_t uid;
  gid_t gid;
  uid_t cuid;
  gid_t cgid;
  mode_t mode;
  unsigned short _seq;
  key_t _key;
} ipc_perm;

typedef __int32_t __darwin_pid_t;

typedef __darwin_pid_t pid_t;

typedef unsigned short shmatt_t;

typedef long __darwin_time_t;

typedef __darwin_time_t time_t;

typedef struct {
  ipc_perm shm_perm;
  size_t shm_segsz;
  pid_t shm_lpid;
  pid_t shm_cpid;
  shmatt_t shm_nattch;
  time_t shm_atime;
  time_t shm_dtime;
  time_t shm_ctime;
  void *shm_internal;
} __shmid_ds_new;

void shm_allocate(const ShmAllocateRequest *request, ShmAllocateResult *result);

ShmCheckExistsResult shm_check_if_exists(const ShmCheckExistsRequest *request);

void shm_delete(const ShmDeleteRequest *request, ShmDeleteResult *result);

void shm_free_error_message(char *error_message);

void shm_get_key(const ShmGetKeyRequest *request, ShmKey *result);

void shm_retrieve(const ShmRetrieveRequest *request, ShmRetrieveResult *result);

extern void *shmat(int arg1, const void *arg2, int arg3);

extern int shmctl(int arg1, int arg2, __shmid_ds_new *arg3);

extern int shmdt(const void *arg1);

extern int shmget(key_t arg1, size_t arg2, int arg3);

extern int shmsys(int arg1);
