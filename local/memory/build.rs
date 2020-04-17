// Copyright 2020 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

#![deny(warnings)]
// Enable all clippy lints except for many of the pedantic ones. It's a shame this needs to be copied and pasted across crates, but there doesn't appear to be a way to include inner attributes from a common source.
#![deny(
  clippy::all,
  clippy::default_trait_access,
  clippy::expl_impl_clone_on_copy,
  clippy::if_not_else,
  clippy::needless_continue,
  clippy::unseparated_literal_suffix,
  clippy::used_underscore_binding
)]
// It is often more clear to show that nothing is being moved.
#![allow(clippy::match_ref_pats)]
// Subjective style.
#![allow(
  clippy::len_without_is_empty,
  clippy::redundant_field_names,
  clippy::too_many_arguments
)]
// Default isn't as big a deal as people seem to think it is.
#![allow(clippy::new_without_default, clippy::new_ret_no_self)]
// Arc<Mutex> can be more clear than needing to grok Orderings:
#![allow(clippy::mutex_atomic)]

use bindgen;

use std::path::PathBuf;

fn main() {
  let bindings = PathBuf::from("src/mmap_bindings.rs");

  /* FIXME: why can't bindgen figure this out itself??? */
  let base = PathBuf::from("/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk/usr/include");

  /* NB: Exporting all the functions and variables necessary for this gist:
   * https://gist.github.com/garcia556/8231e844a90457c99cc72e5add8388e4! */
  bindgen::builder()
    .whitelist_function("shm.*")
    .whitelist_function("m.*map")
    .whitelist_var("O_.*")
    .whitelist_var("S_.*")
    .whitelist_var("PROT_.*")
    .whitelist_var("SHM.*")
    .whitelist_var("MAP_.*")
    .whitelist_var("IPC_.*")
    .header(base.join("sys/ipc.h").to_str().unwrap())
    .header(base.join("sys/shm.h").to_str().unwrap())
    .header(base.join("stdio.h").to_str().unwrap())
    .header(base.join("fcntl.h").to_str().unwrap())
    .header(base.join("unistd.h").to_str().unwrap())
    .raw_line("#![allow(non_camel_case_types)]")
    .raw_line("#![allow(non_upper_case_globals)]")
    .raw_line("#![allow(non_snake_case)]")
    .raw_line("#![allow(dead_code)]")
    .generate()
    .unwrap()
    .write_to_file(bindings)
    .unwrap();
}
