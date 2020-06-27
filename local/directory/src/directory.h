/* Generated with cbindgen:0.8.7 */

#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

typedef enum {
  OidMappingExists,
  OidMappingDoesNotExist,
  OidMappingOtherError,
} DirectoryOidCheckMappingResult;

typedef enum {
  ExpandDirectoriesSucceeded,
  ExpandDirectoriesFailed,
} ExpandDirectoriesResultStatus;

typedef enum {
  UploadDirectoriesSucceeded,
  UploadDirectoriesFailed,
} UploadDirectoriesResultStatus;

typedef struct {
  SizeType size_bytes;
  Fingerprint fingerprint;
} DirectoryDigest;

typedef struct {
  SizeType num_requests;
  const DirectoryDigest *requests;
} ExpandDirectoriesRequest;

typedef struct {
  SizeType relpath_size;
  const char *relpath;
} ChildRelPath;

typedef struct {
  ShmKey key;
  ChildRelPath rel_path;
} FileStat;

typedef struct {
  SizeType num_stats;
  const FileStat *stats;
} PathStats;

typedef struct {
  SizeType num_expansions;
  const DirectoryDigest *digests;
  const PathStats *expansions;
} ExpandDirectoriesMapping;

typedef struct {
  ExpandDirectoriesMapping mapping;
  char *error_message;
  ExpandDirectoriesResultStatus status;
} ExpandDirectoriesResult;

typedef struct {
  SizeType num_path_stats;
  const PathStats *path_stats;
} UploadDirectoriesRequest;

typedef struct {
  ExpandDirectoriesMapping mapping;
  char *error_message;
  UploadDirectoriesResultStatus status;
} UploadDirectoriesResult;

typedef struct {
  void *inner_context;
} TreeTraversalFFIContext;

void directories_expand(const ExpandDirectoriesRequest *request, ExpandDirectoriesResult *result);

void directories_upload(const UploadDirectoriesRequest *request, UploadDirectoriesResult *result);

DirectoryOidCheckMappingResult directory_oid_check_mapping(Fingerprint fingerprint, Digest *result);

void tree_traversal_add_directory(TreeTraversalFFIContext *ctx,
                                  const char *parent_directory,
                                  const char *relpath);

void tree_traversal_add_file(TreeTraversalFFIContext *ctx,
                             const char *parent_directory,
                             const char *relpath,
                             const Digest *digest);

void tree_traversal_add_known_directory(TreeTraversalFFIContext *ctx,
                                        const char *parent_directory,
                                        const char *relpath,
                                        const Digest *digest);

void tree_traversal_destroy_context(TreeTraversalFFIContext *ctx);

void tree_traversal_init_context(TreeTraversalFFIContext *ctx);
