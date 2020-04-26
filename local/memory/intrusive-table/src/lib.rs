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

use std::hash::Hash;
use std::mem;
use std::slice;
use std::sync::atomic::{AtomicUsize, Ordering};

pub enum Error {
  NoMoreSpace(usize),
  OutOfHashableSpots(usize),
  DeleteDidNotExist,
}

pub trait AllocationDescriptor: Default + Eq + PartialEq + Hash + Copy + Clone {
  fn digest(slice: &[u8]) -> Self;
  fn size_bytes(&self) -> usize;
}

pub trait IntrusiveTable<'a, K> {
  fn erase_all(&'a mut self);
  fn retrieve(&'a self, key: &K) -> Option<&'a [u8]>;
  fn allocate(&'a mut self, source: &[u8]) -> Result<&'a [u8], Error>;
  fn delete(&'a mut self, key: &K) -> Result<(), Error>;
}

#[derive(Default)]
struct TableEntry<K: AllocationDescriptor> {
  pub key: K,
  pub offset: AtomicUsize,
}

impl<K: AllocationDescriptor> TableEntry<K> {
  pub fn is_default(&self) -> bool {
    self.key.size_bytes() == 0
  }
}

pub struct IntrusiveAllocator<'a, K: AllocationDescriptor> {
  hash_table: &'a mut [TableEntry<K>],
  allocated_region_extent: &'a AtomicUsize,
  allocatable_data: &'a mut [u8],
}

struct Offset(usize);

pub const HASH_TABLE_SPACE_FACTOR: usize = 10;

impl<'a, K: 'a + AllocationDescriptor> IntrusiveAllocator<'a, K> {
  ///
  /// The entry point for creating an intrusive allocator.
  ///
  pub fn allocator_within_region(owned_region: &'a mut [u8]) -> Self {
    Self::get_layout(owned_region)
  }

  ///
  /// The table is laid out in two consecutive segments as:
  ///
  /// ^[hash table][<extent index>][allocatable data]$
  ///
  fn get_layout(owned_region: &'a mut [u8]) -> Self {
    let hash_table_space = Self::hash_table_space(owned_region);
    let hash_table_num_entries = Self::hash_table_num_entries(owned_region);

    /* [hash table][[extent][allocatable]] */
    let (hash_table_data, extent_and_allocatable_data) =
      owned_region.split_at_mut(hash_table_space);
    let hash_table = unsafe {
      let table_ptr = mem::transmute::<*mut u8, *mut TableEntry<K>>(hash_table_data.as_mut_ptr());
      slice::from_raw_parts_mut(table_ptr, hash_table_num_entries)
    };

    /* [extent][allocatable] */
    let (extent_data, allocatable_data) =
      extent_and_allocatable_data.split_at_mut(mem::size_of::<AtomicUsize>());

    let allocated_region_extent: &'a AtomicUsize = unsafe {
      let extent_ptr = mem::transmute::<*const u8, *const AtomicUsize>(extent_data.as_ptr());
      &*extent_ptr
    };

    IntrusiveAllocator {
      hash_table,
      allocated_region_extent,
      allocatable_data,
    }
  }

  /* These methods describe aspects of the intrusive region that can be computed without reading
   * the data inside it yet. */
  fn full_region_size(owned_region: &mut [u8]) -> usize {
    owned_region.len()
  }

  fn hash_table_space(owned_region: &mut [u8]) -> usize {
    Self::full_region_size(owned_region) / HASH_TABLE_SPACE_FACTOR
  }

  fn hash_table_num_entries(owned_region: &mut [u8]) -> usize {
    Self::hash_table_space(owned_region) / mem::size_of::<TableEntry<K>>()
  }

  /* These methods actually query/traverse the table. */
  fn table_num_entries(&'a self) -> usize {
    self.hash_table.len()
  }

  fn hash_key(&'a self, key: K) -> usize {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::Hasher;

    let mut hasher = DefaultHasher::new();
    Hash::hash(&key, &mut hasher);

    let hash_result: usize = hasher.finish() as usize;
    /* Ensure the result points to an entry within the table. */
    hash_result % self.table_num_entries()
  }

  fn atomic_get_offset(entry: &TableEntry<K>) -> Offset {
    Offset(entry.offset.load(Ordering::SeqCst))
  }

  fn get_offset_slice(&'a self, offset: Offset, key: K) -> &'a [u8] {
    let Offset(begin) = offset;
    let end = begin + key.size_bytes();
    &self.allocatable_data[begin..end]
  }

  fn atomic_get_slice(&'a self, entry: &'a TableEntry<K>) -> &'a [u8] {
    let offset = Self::atomic_get_offset(entry);
    self.get_offset_slice(offset, entry.key)
  }

  fn find_existing_entry(&'a self, key: K) -> Option<&'a TableEntry<K>> {
    /* Incredibly basic linear probing. */
    /* FIXME: probe in a ring past the end of the array! */
    for cur_entry in self.hash_table[self.hash_key(key)..].iter() {
      if cur_entry.is_default() {
        break;
      }
      if cur_entry.key == key {
        return Some(cur_entry);
      }
    }
    return None;
  }

  fn find_entry_to_delete(&'a mut self, key: K) -> Result<(), Error> {
    let initial_index = self.hash_key(key);
    /* Incredibly basic linear probing. */
    /* FIXME: probe in a ring past the end of the array! */
    for cur_entry in self.hash_table[initial_index..].iter_mut() {
      /* The entry did not exist in the table!! */
      if cur_entry.is_default() {
        break;
      }
      if cur_entry.key == key {
        /* FIXME: does this need to be written atomically as well? */
        cur_entry.key = K::default();
        /* TODO: just use TableEntry::default()? */
        cur_entry.offset.store(0, Ordering::SeqCst);
        return Ok(());
      }
    }
    Err(Error::DeleteDidNotExist)
  }

  fn find_first_empty_or_matching_entry(&'a mut self, source: &[u8]) -> Result<&'a [u8], Error> {
    let key = K::digest(source);
    let initial_index = self.hash_key(key);
    let IntrusiveAllocator {
      hash_table,
      allocated_region_extent,
      allocatable_data,
    } = self;
    let table_len = hash_table.len();
    /* Incredibly basic linear probing. */
    /* FIXME: probe in a ring past the end of the array! */
    for cur_entry in hash_table[initial_index..].iter_mut() {
      /* The entry did *not* exist already -- let's populate it. */
      if cur_entry.is_default() {
        /* Atomically bump up the memory line. */
        let previous_extent = allocated_region_extent.fetch_add(key.size_bytes(), Ordering::SeqCst);
        let new_extent = previous_extent + key.size_bytes();
        let new_region = &mut allocatable_data[previous_extent..new_extent];
        /* Write the source data to the new region. */
        new_region.copy_from_slice(source);
        /* Update the entry so that it can be retrieved later. */
        cur_entry.offset.store(previous_extent, Ordering::SeqCst);
        /* FIXME: does this need to be written atomically as well? */
        cur_entry.key = key;
        return Ok(new_region);
      }
      /* If the entry already existed, return the existing slice for it. */
      if cur_entry.key == key {
        let Offset(begin) = Self::atomic_get_offset(cur_entry);
        let end = begin + key.size_bytes();
        return Ok(&allocatable_data[begin..end]);
      }
    }
    /* If the entry couldn't be allocated, error out. */
    Err(Error::OutOfHashableSpots(table_len))
  }
}

impl<'a, K: AllocationDescriptor> IntrusiveTable<'a, K> for IntrusiveAllocator<'a, K> {
  fn erase_all(&'a mut self) {
    /* Apparently this will compile down to vectorized operations -- see
     * https://stackoverflow.com/questions/51732596/what-is-the-equivalent-of-a-safe-memset-for-slices/51732799#51732799 */
    for entry in self.hash_table.iter_mut() {
      *entry = TableEntry::<K>::default();
    }
  }

  fn retrieve(&'a self, key: &K) -> Option<&'a [u8]> {
    self.find_existing_entry(*key).map(|cur_entry| {
      /* We have definitely found an entry. This may be at the same time as another process, so we
       * load the atomic pointer. */
      self.atomic_get_slice(cur_entry)
    })
  }

  fn allocate(&'a mut self, source: &[u8]) -> Result<&'a [u8], Error> {
    self.find_first_empty_or_matching_entry(source)
  }

  fn delete(&'a mut self, key: &K) -> Result<(), Error> {
    self.find_entry_to_delete(*key)
  }
}

#[cfg(test)]
mod tests {
  #[test]
  fn it_works() {
    assert_eq!(2 + 2, 4);
  }
}
