package com.thaiopensource.relaxng;

class NsNameClass implements NameClass {

  private final String namespaceUri;

  NsNameClass(String namespaceUri) {
    this.namespaceUri = namespaceUri;
  }

  public boolean contains(Name name) {
    return this.namespaceUri.equals(name.getNamespaceUri());
  }

  public int hashCode() {
    return namespaceUri.hashCode();
  }

  public boolean equals(Object obj) {
    if (obj == null || !(obj instanceof NsNameClass))
      return false;
    return namespaceUri.equals(((NsNameClass)obj).namespaceUri);
  }

  public void accept(NameClassVisitor visitor) {
    visitor.visitNsName(namespaceUri);
  }

  public boolean isOpen() {
    return true;
  }
}
