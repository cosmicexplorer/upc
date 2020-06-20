// Copyright 2020 Danny McClanahan, at Twitter Inc.
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
#![allow(clippy::redundant_field_names, clippy::too_many_arguments)]
// Default isn't as big a deal as people seem to think it is.
#![allow(clippy::new_without_default, clippy::new_ret_no_self)]
// Arc<Mutex> can be more clear than needing to grok Orderings:
#![allow(clippy::mutex_atomic)]

use glob::glob;

use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;

fn main() {
  let crate_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
  let thrift_dir = crate_dir.join("src/thrift");
  mark_for_change_detection(&thrift_dir);

  let thrift_sources: Vec<PathBuf> = glob(&format!("{}/*.thrift", thrift_dir.display()))
    .unwrap()
    .map(|e| e.unwrap())
    .collect();
  /* panic!("thrift_sources: {:?}", thrift_sources); */

  for src in thrift_sources.iter() {
    assert!(Command::new("thrift")
      .args(&["--gen", "rs"])
      .arg(src.file_name().unwrap())
      .current_dir(&thrift_dir)
      .status()
      .unwrap()
      .success());
  }

  let rust_thrift_basenames: Vec<String> = thrift_sources
    .iter()
    .map(|path_buf| path_buf.file_stem().unwrap().to_os_string())
    .map(|os_str| os_str.to_string_lossy().into())
    .collect();
  let joined_basenames = rust_thrift_basenames.join("|");
  /* panic!("joined_basenames: {:?}", joined_basenames); */

  let rust_thrift_sources: Vec<PathBuf> = thrift_sources
    .into_iter()
    .map(|mut path_buf| {
      path_buf.set_extension("rs");
      path_buf
    })
    .collect();
  /* panic!("rust_thrift_sources: {:?}", rust_thrift_sources); */

  /* Turn the `use <module>;` lines in thrift-generated sources to `use super::<module>;`. */
  assert!(Command::new("sed")
    .args(&["-E", "-i", "-e"])
    .arg(&format!("s#^use ({});#use super::\\1;#g", joined_basenames))
    .args(&rust_thrift_sources)
    .status()
    .unwrap()
    .success());
}

fn mark_for_change_detection(path: &Path) {
  // Restrict re-compilation check to just our input files.
  // See: http://doc.crates.io/build-script.html#outputs-of-the-build-script
  if !path.exists() {
    panic!(
      "Cannot mark non-existing path for change detection: {}",
      path.display()
    );
  }
  for file in walkdir::WalkDir::new(path) {
    println!("cargo:rerun-if-changed={}", file.unwrap().path().display());
  }
}
