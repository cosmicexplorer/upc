use super::*;

use indexmap::IndexMap;

use std::convert::From;
use std::ffi::OsStr;
use std::fmt::Debug;
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};

#[derive(Debug)]
pub enum MerkleTrieError {
  InternalError(String),
  OverlappingPathStats(String),
}
impl From<String> for MerkleTrieError {
  fn from(err: String) -> Self {
    MerkleTrieError::InternalError(err)
  }
}

#[derive(Debug, Clone, Eq, PartialEq, Hash)]
pub struct SinglePathComponent {
  inner: Vec<u8>,
}
impl SinglePathComponent {
  pub fn new(bytes: &[u8]) -> Result<Self, MerkleTrieError> {
    if bytes.is_empty() {
      Err(
        "cannot form SinglePathComponent out of empty byte string"
          .to_string()
          .into(),
      )
    } else {
      Ok(SinglePathComponent {
        inner: bytes.to_vec(),
      })
    }
  }
  pub fn extract_component_bytes(self) -> Vec<u8> {
    self.inner
  }
}

#[derive(Debug, Clone, Eq, PartialEq, Hash)]
pub struct PathComponents {
  components: Vec<SinglePathComponent>,
}
impl PathComponents {
  fn path_bytes(path: &Path) -> &[u8] {
    path.as_os_str().as_bytes()
  }

  fn path_is_empty(path: &Path) -> bool {
    Self::path_bytes(path).is_empty()
  }

  pub fn from_single(single: SinglePathComponent) -> Self {
    PathComponents {
      components: vec![single],
    }
  }

  #[cfg(test)]
  pub fn add_prefix(self, prefix: SinglePathComponent) -> Self {
    PathComponents {
      components: vec![prefix]
        .into_iter()
        .chain(self.components.into_iter())
        .collect(),
    }
  }

  pub fn from_path(path: &Path) -> Result<Self, MerkleTrieError> {
    if !path.is_relative() {
      return Err(
        format!(
          "only relative paths can be split into PathComponents: was {:?}",
          path
        )
        .into(),
      );
    }
    if Self::path_is_empty(path) {
      return Err(
        "empty paths cannot be split into PathComponents!"
          .to_string()
          .into(),
      );
    }

    let components: Vec<SinglePathComponent> = path
      .components()
      .map(|c| c.as_os_str().as_bytes())
      .map(SinglePathComponent::new)
      .collect::<Result<Vec<_>, _>>()?;

    Ok(PathComponents { components })
  }

  pub fn into_path(self) -> PathBuf {
    self
      .components
      .into_iter()
      .fold(PathBuf::from(""), |acc, cur| {
        let bytes = cur.extract_component_bytes();
        let os_str = OsStr::from_bytes(&bytes);
        acc.join(os_str)
      })
  }

  pub fn into_split_last(mut self) -> (SinglePathComponent, Vec<SinglePathComponent>) {
    let last = self.components.pop().unwrap();
    (last, self.components)
  }
}

#[derive(Debug)]
pub enum MerkleTrieEntry<File: Debug> {
  File(File),
  SubTrie(MerkleTrie<File>),
}

/* This distinct enum is useful, even if there is only one case! */
pub enum MerkleTrieTerminalEntry<File> {
  File(File),
}

pub struct FileStat<File> {
  pub components: PathComponents,
  pub terminal: MerkleTrieTerminalEntry<File>,
}

#[derive(Debug)]
pub struct MerkleTrie<File: Debug> {
  map: IndexMap<SinglePathComponent, MerkleTrieEntry<File>>,
}
impl<File: Debug> MerkleTrie<File> {
  pub fn new() -> Self {
    MerkleTrie {
      map: IndexMap::new(),
    }
  }

  pub fn extract_mapping(self) -> IndexMap<SinglePathComponent, MerkleTrieEntry<File>> {
    self.map
  }

  #[cfg(test)]
  pub fn extract_flattened(self) -> Vec<(PathComponents, File)> {
    let mut files: Vec<(PathComponents, File)> = Vec::new();
    let mut sub_tries: Vec<(SinglePathComponent, Self)> = Vec::new();

    for (path_component, entry) in self.map.into_iter() {
      match entry {
        MerkleTrieEntry::File(key) => {
          let single_path = PathComponents::from_single(path_component);
          files.push((single_path, key));
        }
        MerkleTrieEntry::SubTrie(sub_trie) => {
          sub_tries.push((path_component, sub_trie));
        }
      }
    }

    let sub_files: Vec<(PathComponents, File)> = sub_tries
      .into_iter()
      .flat_map(|(prefix, sub_trie)| {
        let sub_flattened: Vec<(PathComponents, File)> = sub_trie
          .extract_flattened()
          .into_iter()
          .map(|(components, file)| (components.add_prefix(prefix.clone()), file))
          .collect();
        sub_flattened
      })
      .collect();

    files.into_iter().chain(sub_files.into_iter()).collect()
  }

  pub fn from(map: IndexMap<SinglePathComponent, MerkleTrieEntry<File>>) -> Self {
    MerkleTrie { map }
  }

  fn advance_path(&mut self, file_stat: FileStat<File>) -> Result<(), MerkleTrieError> {
    let FileStat {
      components,
      terminal,
    } = file_stat;

    let (last_component, intermediate_components) = components.into_split_last();

    let mut cur_trie: &mut Self = self;

    /* Get the penultimate trie. */
    for component in intermediate_components.into_iter() {
      /* Get the current subdir trie, or create it if it doesn't exist. */
      let cur_trie_entry = cur_trie
        .map
        .entry(component)
        .or_insert_with(|| MerkleTrieEntry::SubTrie(MerkleTrie::<File>::new()));
      /* If there was already a file in some location (e.g. at a/b.txt), we can't allow another file
       * to be written as if the first file was a directory (e.g. a/b.txt/c.txt). */
      cur_trie = match &mut *cur_trie_entry {
        MerkleTrieEntry::SubTrie(ref mut sub_trie) => sub_trie,
        MerkleTrieEntry::File(key) => {
          /* TODO: add cur_trie and self to error message here!! */
          return Err(MerkleTrieError::OverlappingPathStats(format!(
            "expected sub trie: got file for key {:?} instead!",
            key,
          )));
        }
      };
    }

    /* Validate that no file or directory has been written to this path before. */
    if let Some(existing_entry) = cur_trie.map.get(&last_component) {
      Err(MerkleTrieError::OverlappingPathStats(format!(
            "found existing entry {:?} in cur trie {:?} -- should be empty (no file or containing directory should have the same path in a set of path stats!)!",
            existing_entry, cur_trie,
          )))
    } else {
      let to_insert: MerkleTrieEntry<File> = match terminal {
        MerkleTrieTerminalEntry::File(key) => MerkleTrieEntry::File(key),
      };
      cur_trie.map.insert(last_component, to_insert);
      Ok(())
    }
  }

  pub fn populate(&mut self, file_stats: Vec<FileStat<File>>) -> Result<(), MerkleTrieError> {
    for single_file_stat in file_stats.into_iter() {
      self.advance_path(single_file_stat)?;
    }
    Ok(())
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  use std::path::Path;

  fn path_components(s: &str) -> Result<PathComponents, MerkleTrieError> {
    PathComponents::from_path(Path::new(s))
  }

  fn make_file_stats<'a, 'b>(input: &'a [(PathComponents, &'b str)]) -> Vec<FileStat<&'b str>> {
    input
      .iter()
      .map(|(components, file)| FileStat {
        components: components.clone(),
        terminal: MerkleTrieTerminalEntry::File(*file),
      })
      .collect()
  }

  #[test]
  fn populate_and_extract() -> Result<(), MerkleTrieError> {
    let input: Vec<(PathComponents, &str)> = vec![
      (path_components("a.txt")?, "this is a.txt!"),
      (path_components("b.txt")?, "this is b.txt!"),
      (path_components("d/e.txt")?, "this is d/e.txt!"),
    ];
    let file_stats = make_file_stats(&input);

    let mut trie = MerkleTrie::<&str>::new();
    trie.populate(file_stats)?;

    let output: Vec<(PathComponents, &str)> = trie.extract_flattened();
    assert_eq!(input, output);
    Ok(())
  }

  #[test]
  fn catches_overlapping_paths() -> Result<(), MerkleTrieError> {
    let overlapping_file_paths = vec![
      (path_components("a.txt")?, "first"),
      (path_components("a.txt")?, "second"),
    ];
    assert!(
      match MerkleTrie::<&str>::new().populate(make_file_stats(&overlapping_file_paths)) {
        Err(MerkleTrieError::OverlappingPathStats(_)) => true,
        _ => false,
      }
    );

    let overlapping_file_and_dir_path = vec![
      (path_components("a.txt")?, "first"),
      (path_components("a.txt/b.txt")?, "this isn't allowed"),
    ];
    assert!(match MerkleTrie::<&str>::new()
      .populate(make_file_stats(&overlapping_file_and_dir_path))
    {
      Err(MerkleTrieError::OverlappingPathStats(_)) => true,
      _ => false,
    });

    Ok(())
  }
}
