package com.github.marschall.memoryfilesystem;

import static com.github.marschall.memoryfilesystem.AutoReleaseLock.autoRelease;
import static java.nio.file.attribute.AclEntryPermission.EXECUTE;
import static java.nio.file.attribute.AclEntryPermission.READ_ACL;
import static java.nio.file.attribute.AclEntryPermission.READ_DATA;
import static java.nio.file.attribute.AclEntryPermission.WRITE_ACL;
import static java.nio.file.attribute.AclEntryPermission.WRITE_DATA;
import static java.nio.file.attribute.AclEntryType.ALLOW;
import static java.nio.file.attribute.AclEntryType.DENY;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.FileSystemException;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

abstract class MemoryEntry {

  private final String originalName;

  // protected by read and write locks
  private long lastModifiedTime;
  private long lastAccessTime;
  private long creationTime;
  private final MemoryFileSystem fileSystem;

  private final ReadWriteLock lock;

  private final Map<String, InitializingFileAttributeView> additionalViews;

  MemoryEntry(String originalName, EntryCreationContext context) {
    this.originalName = originalName;
    this.fileSystem = context.fileSystem;
    this.lock = new ReentrantReadWriteLock();
    long now = this.getNow();
    this.lastAccessTime = now;
    this.lastModifiedTime = now;
    this.creationTime = now;
    if (context.additionalViews.isEmpty()) {
      this.additionalViews = Collections.emptyMap();
    } else if (context.additionalViews.size() == 1) {
      InitializingFileAttributeView view = this.instantiate(context.firstView(), context);
      this.additionalViews = Collections.singletonMap(view.name(), view);
    } else {
      this.additionalViews = new HashMap<>(context.additionalViews.size());
      for (Class<? extends FileAttributeView> viewClass : context.additionalViews) {
        InitializingFileAttributeView view = this.instantiate(viewClass, context);
        this.additionalViews.put(view.name(), view);
      }
    }
  }

  void initializeAttributes(MemoryEntry other) throws IOException {
    try (AutoRelease lock = this.writeLock()) {
      this.getInitializingFileAttributeView().initializeFrom(other.getBasicFileAttributeView());
      for (InitializingFileAttributeView view : this.additionalViews.values()) {
        view.initializeFrom(other.additionalViews);
      }
    }
  }


  void initializeRoot() {
    try (AutoRelease lock = this.readLock()) {
      for (InitializingFileAttributeView view : this.additionalViews.values()) {
        view.initializeRoot();
      }
    }
  }

  private InitializingFileAttributeView instantiate(Class<? extends FileAttributeView> viewClass,
          EntryCreationContext context) {
    if (viewClass == PosixFileAttributeView.class) {
      return new MemoryPosixFileAttributeView(this, context);
    } else if (viewClass == DosFileAttributeView.class) {
      return new MemoryDosFileAttributeView(this);
    } else if (viewClass == UserDefinedFileAttributeView.class) {
      return new MemoryUserDefinedFileAttributeView(this);
    } else if (viewClass == AclFileAttributeView.class) {
      return new MemoryAclFileAttributeView(this, context);
    } else {
      throw new IllegalArgumentException("unknown file attribute view: " + viewClass);
    }
  }


  String getOriginalName() {
    return this.originalName;
  }

  long getNow() {
    return System.currentTimeMillis();
  }


  AutoRelease readLock() {
    return autoRelease(this.lock.readLock());
  }

  AutoRelease writeLock() {
    return autoRelease(this.lock.writeLock());
  }

  FileTime lastModifiedTime() {
    return FileTime.fromMillis(this.lastModifiedTime);
  }

  FileTime lastAccessTime() {
    return FileTime.fromMillis(this.lastAccessTime);
  }

  FileTime creationTime() {
    return FileTime.fromMillis(this.creationTime);
  }

  void checkAccess(AccessMode... modes) throws AccessDeniedException {
    try (AutoRelease lock = this.readLock()) {
      AccessMode unsupported = this.getUnsupported(modes);
      if (unsupported != null) {
        throw new UnsupportedOperationException("access mode " + unsupported + " is not supported");
      }
      for (Object attributeView : this.additionalViews.values()) {
        if (attributeView instanceof AccessCheck) {
          AccessCheck accessCheck = (AccessCheck) attributeView;
          accessCheck.checkAccess(modes);
        }
      }
    }
  }

  void checkAccess(AccessMode mode) throws AccessDeniedException {
    try (AutoRelease lock = this.readLock()) {
      AccessMode unsupported = this.getUnsupported(mode);
      if (unsupported != null) {
        throw new UnsupportedOperationException("access mode " + unsupported + " is not supported");
      }
      for (Object attributeView : this.additionalViews.values()) {
        if (attributeView instanceof AccessCheck) {
          AccessCheck accessCheck = (AccessCheck) attributeView;
          accessCheck.checkAccess(mode);
        }
      }
    }
  }

  private UserPrincipal getCurrentUser() {
    UserPrincipal user = CurrentUser.get();
    if (user == null) {
      return this.fileSystem.getUserPrincipalLookupService().getDefaultUser();
    } else {
      return user;
    }
  }

  private GroupPrincipal getCurrentGroup() {
    // TODO special case for just one user
    return CurrentGroup.get();
  }

  private AccessMode getUnsupported(AccessMode... modes) {
    for (AccessMode mode : modes) {
      if (!(mode == AccessMode.READ || mode == AccessMode.WRITE || mode == AccessMode.EXECUTE)) {
        return mode;
      }
    }
    return null;
  }

  private AccessMode getUnsupported(AccessMode mode) {
    if (!(mode == AccessMode.READ || mode == AccessMode.WRITE || mode == AccessMode.EXECUTE)) {
      return mode;
    }
    return null;
  }

  void modified() {
    // No write lock because this was to be folded in an operation with a write lock
    long now = this.getNow();
    this.lastAccessTime = now;
    this.lastModifiedTime = now;
  }

  void accessed() {
    // No write lock because this was to be folded in an operation with a write lock
    this.lastAccessTime = this.getNow();
  }

  void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws AccessDeniedException {
    try (AutoRelease lock = this.writeLock()) {
      this.checkAccess(AccessMode.WRITE);
      if (lastModifiedTime != null) {
        this.lastModifiedTime = lastModifiedTime.toMillis();
      }
      if (lastAccessTime != null) {
        this.lastAccessTime = lastAccessTime.toMillis();
      }
      if (createTime != null) {
        this.creationTime = createTime.toMillis();
      }
    }
  }

  <A extends FileAttributeView> A getFileAttributeView(Class<A> type) throws AccessDeniedException {
    try (AutoRelease lock = this.readLock()) {
      this.checkAccess(AccessMode.READ);
      if (type == BasicFileAttributeView.class) {
        return (A) this.getBasicFileAttributeView();
      } else {
        String name = null;
        if (type == FileOwnerAttributeView.class) {
          // owner is either mapped to POSIX or ACL
          // TODO POSIX and ACL?
          if (this.additionalViews.containsKey(FileAttributeViews.POSIX)) {
            name = FileAttributeViews.POSIX;
          } else if (this.additionalViews.containsKey(FileAttributeViews.ACL)) {
            name = FileAttributeViews.ACL;
          }
        } else {
          name = FileAttributeViews.mapAttributeView(type);
        }
        if (name == null) {
          throw new UnsupportedOperationException("file attribute view" + type + " not supported");
        }
        FileAttributeView view = this.additionalViews.get(name);
        if (view != null) {
          return (A) view;
        } else {
          throw new UnsupportedOperationException("file attribute view" + type + " not supported");
        }
      }
    }
  }

  <A extends BasicFileAttributes> A readAttributes(Class<A> type) throws IOException {
    try (AutoRelease lock = this.readLock()) {
      this.checkAccess(AccessMode.READ);
      if (type == BasicFileAttributes.class) {
        return (A) this.getBasicFileAttributeView().readAttributes();
      } else {
        String viewName = FileAttributeViews.mapFileAttributes(type);
        if (viewName != null) {
          FileAttributeView view = this.additionalViews.get(viewName);
          if (view instanceof BasicFileAttributeView) {
            return (A) ((BasicFileAttributeView) view).readAttributes();
          } else {
            throw new UnsupportedOperationException("file attributes " + type + " not supported");
          }
        } else {
          throw new UnsupportedOperationException("file attributes " + type + " not supported");
        }
      }
    }
  }

  abstract InitializingFileAttributeView getInitializingFileAttributeView();

  abstract BasicFileAttributeView getBasicFileAttributeView();

  abstract class MemoryEntryFileAttributesView implements InitializingFileAttributeView, BasicFileAttributeView {

    @Override
    public String name() {
      return FileAttributeViews.BASIC;
    }

    @Override
    public void initializeFrom(BasicFileAttributeView basicFileAttributeView) throws IOException {
      BasicFileAttributes otherAttributes = basicFileAttributeView.readAttributes();
      this.setTimes(otherAttributes.lastModifiedTime(), otherAttributes.lastAccessTime(), otherAttributes.creationTime());

    }

    @Override
    public void initializeFrom(Map<String, ? extends FileAttributeView> additionalAttributes) {
      // ignore
    }

    @Override
    public void initializeRoot() {
      // ignore
    }

    @Override
    public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException {
      MemoryEntry.this.setTimes(lastModifiedTime, lastAccessTime, createTime);
    }

  }

  static abstract class MemoryEntryFileAttributes implements BasicFileAttributes {

    private final FileTime lastModifiedTime;
    private final FileTime lastAccessTime;
    private final FileTime creationTime;
    private final Object fileKey;

    MemoryEntryFileAttributes(Object fileKey, FileTime lastModifiedTime, FileTime lastAccessTime, FileTime creationTime) {
      this.fileKey = fileKey;
      this.lastModifiedTime = lastModifiedTime;
      this.lastAccessTime = lastAccessTime;
      this.creationTime = creationTime;
    }

    @Override
    public FileTime lastModifiedTime() {
      return this.lastModifiedTime;
    }

    @Override
    public FileTime lastAccessTime() {
      return this.lastAccessTime;
    }

    @Override
    public FileTime creationTime() {
      return this.creationTime;
    }

    @Override
    public Object fileKey() {
      return this.fileKey;
    }

  }

  static abstract class DelegatingFileAttributesView implements InitializingFileAttributeView {

    final MemoryEntry entry;

    DelegatingFileAttributesView(MemoryEntry entry) {
      this.entry = entry;
    }

    public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException {
      this.entry.getBasicFileAttributeView().setTimes(lastModifiedTime, lastAccessTime, createTime);
    }

    @Override
    public void initializeFrom(BasicFileAttributeView basicFileAttributeView) {
      // ignore
    }

    @Override
    public void initializeRoot() {
      // ignore
    }

    @Override
    public void initializeFrom(Map<String, ? extends FileAttributeView> additionalAttributes) {
      FileAttributeView selfAttributes = additionalAttributes.get(this.name());
      if (selfAttributes != null) {
        this.initializeFromSelf(selfAttributes);
      }
    }

    abstract void initializeFromSelf(FileAttributeView selfAttributes);

  }

  static class MemoryAclFileAttributeView extends MemoryFileOwnerAttributeView implements AclFileAttributeView, AccessCheck {

    private List<AclEntry> acl;

    MemoryAclFileAttributeView(MemoryEntry entry, EntryCreationContext context) {
      super(entry, context);
      this.acl = Collections.emptyList();
    }

    @Override
    public String name() {
      return FileAttributeViews.ACL;
    }

    @Override
    void initializeFromSelf(FileAttributeView selfAttributes) {
      this.acl = new ArrayList<>(((MemoryAclFileAttributeView) selfAttributes).acl);
    }

    @Override
    public void setAcl(List<AclEntry> acl) throws IOException {
      this.checkAccess(WRITE_ACL);
      try (AutoRelease lock = this.entry.writeLock()) {
        this.acl = new ArrayList<>(acl); // will do null check
      }
    }

    @Override
    public List<AclEntry> getAcl() throws IOException {
      this.checkAccess(READ_ACL);
      try (AutoRelease lock = this.entry.readLock()) {
        return new ArrayList<>(this.acl);
      }
    }

    public void checkAccess(AclEntryPermission mode) throws AccessDeniedException {
      // TODO "OWNER@", "GROUP@", and "EVERYONE@"
      UserPrincipal currentUser = this.entry.getCurrentUser();
      GroupPrincipal currentGroup = this.entry.getCurrentGroup();
      for (AclEntry entry : this.acl) {
        UserPrincipal principal = entry.principal();
        if (principal.equals(currentUser) || principal.equals(currentGroup)) {
          Set<AclEntryPermission> permissions = entry.permissions();
          boolean applies = permissions.contains(mode);
          AclEntryType type = entry.type();
          if (applies) {
            if (type == ALLOW) {
              return;
            }
            if (type == DENY) {
              // TODO pass in file
              throw new AccessDeniedException(null);
            }
          }
        }
      }
    }

    @Override
    public void checkAccess(AccessMode mode) throws AccessDeniedException {
      switch (mode) {
        case READ:
          this.checkAccess(READ_DATA);
          break;
        case WRITE:
          this.checkAccess(WRITE_DATA);
          break;
        case EXECUTE:
          this.checkAccess(EXECUTE);
          break;
        default:
          throw new UnsupportedOperationException("access mode " + mode + " is not supported");
      }
    }

    @Override
    public void checkAccess(AccessMode[] modes) throws AccessDeniedException {
      for (AccessMode mode : modes) {
        this.checkAccess(mode);
      }
    }



  }

  static class MemoryDosFileAttributeView extends DelegatingFileAttributesView implements DosFileAttributeView, AccessCheck {

    private boolean readOnly;
    private boolean hidden;
    private boolean system;
    private boolean archive;

    MemoryDosFileAttributeView(MemoryEntry entry) {
      super(entry);
    }

    @Override
    public String name() {
      return FileAttributeViews.DOS;
    }

    @Override
    public void initializeRoot() {
      this.hidden = true;
      this.system = true;
    }

    @Override
    public DosFileAttributes readAttributes() throws IOException {
      this.entry.checkAccess(AccessMode.READ);
      try (AutoRelease lock = this.entry.readLock()) {
        BasicFileAttributeView view = this.entry.getFileAttributeView(BasicFileAttributeView.class);
        return new MemoryDosFileAttributes(view.readAttributes(), this.readOnly, this.hidden, this.system, this.archive);
      }
    }

    @Override
    void initializeFromSelf(FileAttributeView selfAttributes) {
      MemoryDosFileAttributeView other = (MemoryDosFileAttributeView) selfAttributes;
      this.readOnly = other.readOnly;
      this.hidden = other.hidden;
      this.system = other.system;
      this.archive = other.archive;
    }

    @Override
    public void setReadOnly(boolean value) throws IOException {
      try (AutoRelease lock = this.entry.writeLock()) {
        // don't check access
        this.readOnly = value;
      }
    }

    @Override
    public void setHidden(boolean value)  throws IOException{
      try (AutoRelease lock = this.entry.writeLock()) {
        // don't check access
        this.hidden = value;
      }

    }

    @Override
    public void setSystem(boolean value) throws IOException {
      try (AutoRelease lock = this.entry.writeLock()) {
        // don't check access
        this.system = value;
      }

    }

    @Override
    public void setArchive(boolean value) throws IOException {
      try (AutoRelease lock = this.entry.writeLock()) {
        // don't check access
        this.archive = value;
      }

    }

    @Override
    public void checkAccess(AccessMode mode) throws AccessDeniedException {
      switch (mode) {
        case READ:
          // always fine
          break;
        case WRITE:
          if (this.readOnly) {
            // TODO pass in file
            throw new AccessDeniedException(null);
          }
          break;
        case EXECUTE:
          // always fine
          break;
        default:
          throw new UnsupportedOperationException("access mode " + mode + " is not supported");
      }
    }

    @Override
    public void checkAccess(AccessMode[] modes) throws AccessDeniedException {
      for (AccessMode mode : modes) {
        this.checkAccess(mode);
      }
    }
  }


  static class DelegatingAttributes implements BasicFileAttributes {

    private final BasicFileAttributes delegate;

    DelegatingAttributes(BasicFileAttributes delegate) {
      this.delegate = delegate;
    }

    @Override
    public FileTime lastModifiedTime() {
      return this.delegate.lastModifiedTime();
    }

    @Override
    public FileTime lastAccessTime() {
      return this.delegate.lastAccessTime();
    }

    @Override
    public FileTime creationTime() {
      return this.delegate.creationTime();
    }

    @Override
    public boolean isRegularFile() {
      return this.delegate.isRegularFile();
    }

    @Override
    public boolean isDirectory() {
      return this.delegate.isDirectory();
    }

    @Override
    public boolean isSymbolicLink() {
      return this.delegate.isSymbolicLink();
    }

    @Override
    public boolean isOther() {
      return this.delegate.isOther();
    }

    @Override
    public long size() {
      return this.delegate.size();
    }

    @Override
    public Object fileKey() {
      return this.delegate.fileKey();
    }

  }

  static class MemoryPosixFileAttributes extends DelegatingAttributes implements PosixFileAttributes {

    private final UserPrincipal owner;
    private final GroupPrincipal group;
    private final Set<PosixFilePermission> permissions;

    MemoryPosixFileAttributes(BasicFileAttributes delegate, UserPrincipal owner, GroupPrincipal group, Set<PosixFilePermission> permissions) {
      super(delegate);
      this.owner = owner;
      this.group = group;
      this.permissions = permissions;
    }

    @Override
    public UserPrincipal owner() {
      return this.owner;
    }

    @Override
    public GroupPrincipal group() {
      return this.group;
    }

    @Override
    public Set<PosixFilePermission> permissions() {
      return this.permissions;
    }

  }

  static class MemoryDosFileAttributes extends DelegatingAttributes implements DosFileAttributes {

    private final boolean readOnly;
    private final boolean hidden;
    private final boolean system;
    private final boolean archive;

    MemoryDosFileAttributes(BasicFileAttributes delegate, boolean readOnly, boolean hidden, boolean system, boolean archive) {
      super(delegate);
      this.readOnly = readOnly;
      this.hidden = hidden;
      this.system = system;
      this.archive = archive;
    }

    @Override
    public boolean isReadOnly() {
      return this.readOnly;
    }

    @Override
    public boolean isHidden() {
      return this.hidden;
    }

    @Override
    public boolean isArchive() {
      return this.archive;
    }

    @Override
    public boolean isSystem() {
      return this.system;
    }

  }


  static abstract class MemoryFileOwnerAttributeView extends DelegatingFileAttributesView implements FileOwnerAttributeView {

    private UserPrincipal owner;

    MemoryFileOwnerAttributeView(MemoryEntry entry, EntryCreationContext context) {
      super(entry);
      if (context.user == null) {
        throw new NullPointerException("owner");
      }
      this.owner = context.user;
    }

    @Override
    public UserPrincipal getOwner() {
      try (AutoRelease lock = this.entry.readLock()) {
        return this.owner;
      }
    }

    @Override
    public void setOwner(UserPrincipal owner) throws IOException {
      // TODO check same file system
      if (owner == null) {
        throw new IllegalArgumentException("owner must not be null");
      }
      try (AutoRelease lock = this.entry.writeLock()) {
        this.entry.checkAccess(AccessMode.WRITE);
        this.owner = owner;
      }
    }

  }

  static class MemoryPosixFileAttributeView extends MemoryFileOwnerAttributeView implements PosixFileAttributeView, AccessCheck {

    private GroupPrincipal group;
    private int perms;

    MemoryPosixFileAttributeView(MemoryEntry entry, EntryCreationContext context) {
      super(entry, context);
      if (context.group == null) {
        throw new NullPointerException("group");
      }
      this.group = context.group;
      this.perms = toMask(context.umask);
    }


    @Override
    public String name() {
      return FileAttributeViews.POSIX;
    }

    @Override
    void initializeFromSelf(FileAttributeView selfAttributes) {
      MemoryPosixFileAttributeView other = (MemoryPosixFileAttributeView) selfAttributes;
      this.group = other.group;
      this.perms = other.perms;
    }

    @Override
    public void setGroup(GroupPrincipal group) throws IOException {
      // TODO check same file system
      if (group == null) {
        throw new IllegalArgumentException("group must not be null");
      }
      try (AutoRelease lock = this.entry.writeLock()) {
        this.entry.checkAccess(AccessMode.WRITE);
        this.group = group;
      }
    }

    @Override
    public PosixFileAttributes readAttributes() throws IOException {
      this.entry.checkAccess(AccessMode.READ);
      try (AutoRelease lock = this.entry.readLock()) {
        BasicFileAttributeView view = this.entry.getFileAttributeView(BasicFileAttributeView.class);
        return new MemoryPosixFileAttributes(view.readAttributes(), this.getOwner(), this.group, toSet(this.perms));
      }
    }

    @Override
    public void setPermissions(Set<PosixFilePermission> perms) throws IOException {
      if (perms == null) {
        throw new IllegalArgumentException("permissions must not be null");
      }
      try (AutoRelease lock = this.entry.writeLock()) {
        this.entry.checkAccess(AccessMode.WRITE);
        this.perms = toMask(perms);
      }
    }

    @Override
    public void checkAccess(AccessMode mode) throws AccessDeniedException {
      UserPrincipal user = this.entry.getCurrentUser();
      PosixFilePermission permission;
      if (user == this.getOwner()) {
        permission = this.translateOwnerMode(mode);
      } else {
        GroupPrincipal group = this.entry.getCurrentGroup();
        if (group == this.group) {
          permission = this.translateGroupMode(mode);
        } else {
          permission = this.translateOthersMode(mode);
        }
      }
      int flag = 1 << permission.ordinal() & this.perms;
      if (flag == 0) {
        // TODO pass in file
        throw new AccessDeniedException(null);
      }
    }

    @Override
    public void checkAccess(AccessMode[] modes) throws AccessDeniedException {
      for (AccessMode mode : modes) {
        // TODO optimize user lookup
        this.checkAccess(mode);
      }
    }

    private PosixFilePermission translateOwnerMode(AccessMode mode) {
      switch (mode) {
        case READ:
          return PosixFilePermission.OWNER_READ;
        case WRITE:
          return PosixFilePermission.OWNER_WRITE;
        case EXECUTE:
          return PosixFilePermission.OWNER_EXECUTE;
        default:
          throw new UnsupportedOperationException("access mode " + mode + " is not supported");
      }
    }

    private PosixFilePermission translateGroupMode(AccessMode mode) {
      switch (mode) {
        case READ:
          return PosixFilePermission.GROUP_READ;
        case WRITE:
          return PosixFilePermission.GROUP_WRITE;
        case EXECUTE:
          return PosixFilePermission.GROUP_EXECUTE;
        default:
          throw new UnsupportedOperationException("access mode " + mode + " is not supported");
      }
    }

    private PosixFilePermission translateOthersMode(AccessMode mode) {
      switch (mode) {
        case READ:
          return PosixFilePermission.OTHERS_READ;
        case WRITE:
          return PosixFilePermission.OTHERS_WRITE;
        case EXECUTE:
          return PosixFilePermission.OTHERS_EXECUTE;
        default:
          throw new UnsupportedOperationException("access mode " + mode + " is not supported");
      }
    }

  }

  static class MemoryUserDefinedFileAttributeView extends DelegatingFileAttributesView implements UserDefinedFileAttributeView, BasicFileAttributeView {

    // can potentially be null
    // try to delay instantiating as long as possible to keep per file object overhead minimal
    // protected by lock of memory entry
    private Map<String, byte[]> values;

    MemoryUserDefinedFileAttributeView(MemoryEntry entry) {
      super(entry);
    }

    @Override
    public BasicFileAttributes readAttributes() throws IOException {
      throw new UnsupportedOperationException("readAttributes");
    }

    @Override
    void initializeFromSelf(FileAttributeView selfAttributes) {
      MemoryUserDefinedFileAttributeView other = (MemoryUserDefinedFileAttributeView) selfAttributes;
      if (other.values == null) {
        this.values = null;
      } else {
        this.values = new HashMap<>(other.values.size());
        for (Entry<String, byte[]> entry : other.values.entrySet()) {
          this.values.put(entry.getKey(), entry.getValue().clone());
        }
      }

    }

    private Map<String, byte[]> getValues() {
      if (this.values == null) {
        this.values = new HashMap<>(3);
      }
      return this.values;
    }

    @Override
    public String name() {
      return FileAttributeViews.USER;
    }

    @Override
    public List<String> list() throws IOException {
      try (AutoRelease lock = this.entry.readLock()) {
        if (this.values == null) {
          return Collections.emptyList();
        } else {
          Set<String> keys = this.getValues().keySet();
          return new ArrayList<String>(keys);
        }
      }
    }

    @Override
    public int size(String name) throws IOException {
      try (AutoRelease lock = this.entry.readLock()) {
        byte[] value = this.getValue(name);
        return value.length;
      }
    }

    private byte[] getValue(String name) throws IOException {
      if (name == null) {
        throw new NullPointerException("name is null");
      }
      if (this.values == null) {
        throw new FileSystemException(null, null, "attribute " + name + " not present");
      }
      byte[] value = this.values.get(name);
      if (value == null) {
        throw new FileSystemException(null, null, "attribute " + name + " not present");
      }
      return value;
    }

    @Override
    public int read(String name, ByteBuffer dst) throws IOException {
      try (AutoRelease lock = this.entry.readLock()) {
        byte[] value = this.getValue(name);
        int remaining = dst.remaining();
        int required = value.length;
        if (remaining < required) {
          throw new FileSystemException(null, null, required + " bytes in buffer required but only " + remaining + " available");
        }
        int startPosition = dst.position();
        dst.put(value);
        int endPosition = dst.position();
        // TODO check if successful?
        return endPosition - startPosition;
      }
    }

    @Override
    public int write(String name, ByteBuffer src) throws IOException {
      try (AutoRelease lock = this.entry.writeLock()) {
        if (name == null) {
          throw new NullPointerException("name is null");
        }
        if (src == null) {
          throw new NullPointerException("buffer is null");
        }
        int remaining = src.remaining();
        byte[] dst = new byte[remaining];
        int startPosition = src.position();
        src.get(dst);
        int endPosition = src.position();
        this.getValues().put(name, dst);
        // TODO check if successful?
        return endPosition - startPosition;
      }
    }

    @Override
    public void delete(String name) throws IOException {
      try (AutoRelease lock = this.entry.writeLock()) {
        if (this.values != null) {
          if (name == null) {
            throw new NullPointerException("name is null");
          }
          this.values.remove(name);
        }
      }
    }
  }


  static Set<PosixFilePermission> toSet(int mask) {
    Set<PosixFilePermission> set = EnumSet.noneOf(PosixFilePermission.class);
    for (PosixFilePermission permission : PosixFilePermission.values()) {
      int flag = 1 << permission.ordinal() & mask;
      if (flag != 0) {
        set.add(permission);
      }
    }
    return set;
  }

  static int toMask(Set<PosixFilePermission> permissions) {
    int mask = 0;
    for (PosixFilePermission permission : permissions) {
      mask |= 1 << permission.ordinal();
    }
    return mask;
  }

}
