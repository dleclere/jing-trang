package com.thaiopensource.relaxng.mns;

import com.thaiopensource.relaxng.IncorrectSchemaException;
import com.thaiopensource.relaxng.Schema;
import com.thaiopensource.relaxng.ValidatorHandler;
import com.thaiopensource.relaxng.parse.sax.XmlBaseHandler;
import com.thaiopensource.util.Localizer;
import com.thaiopensource.util.Uri;
import com.thaiopensource.xml.util.StringSplitter;
import com.thaiopensource.xml.util.WellKnownNamespaces;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.LocatorImpl;

import java.io.IOException;
import java.io.StringReader;
import java.util.Hashtable;
import java.util.Enumeration;

class SchemaImpl implements Schema {
  static final String BEARER_URI = "http://www.thaiopensoure.com/mns/instance";
  static final String BEARER_LOCAL_NAME = "globalAttributesBearer";
  private static final String MNS_URI = "http://www.thaiopensource.com/ns/mns";
  private static final String BEARER_PREFIX = "m";
  private final Hashtable modeMap = new Hashtable();
  private Mode startMode;
  private static final String DEFAULT_MODE_NAME = "#default";

  static private final class WrappedIOException extends RuntimeException {
    private final IOException exception;

    private WrappedIOException(IOException exception) {
      this.exception = exception;
    }

    private IOException getException() {
      return exception;
    }
  }

  static class ElementAction {
    private final Schema schema;
    private final Mode mode;
    private final boolean prune;
    private final Hashset covered = new Hashset();

    ElementAction(Schema schema, Mode mode, boolean prune) {
      this.schema = schema;
      this.mode = mode;
      this.prune = prune;
    }

    Mode getMode() {
      return mode;
    }

    Schema getSchema() {
      return schema;
    }

    boolean getPrune() {
      return prune;
    }

    Hashset getCoveredNamespaces() {
      return covered;
    }
  }

  static class Mode {
    private Locator whereDefined;
    private boolean defined = false;
    private boolean strict = false;
    private boolean strictDefined = false;
    private final Hashtable elementMap = new Hashtable();
    private final Hashtable attributesMap = new Hashtable();

    boolean isStrict() {
      return strict;
    }

    Schema getAttributesSchema(String ns) {
      return (Schema)attributesMap.get(ns);
    }

    ElementAction getElementAction(String ns) {
      return (ElementAction)elementMap.get(ns);
    }
  }

  private class Handler extends DelegatingContentHandler {
    private final MnsSchemaFactory factory;
    private final ValidatorHandler validator;
    private ElementAction currentElementAction;
    private boolean hadError = false;
    private final ErrorHandler eh;
    private final Localizer localizer = new Localizer(SchemaImpl.class);
    private Locator locator;
    private final XmlBaseHandler xmlBaseHandler = new XmlBaseHandler();
    private int foreignDepth = 0;

    Handler(MnsSchemaFactory factory, ValidatorHandler validator, ErrorHandler eh) {
      super(validator);
      this.factory = factory;
      this.validator = validator;
      this.eh = eh;
    }

    public void setDocumentLocator(Locator locator) {
      super.setDocumentLocator(locator);
      xmlBaseHandler.setLocator(locator);
      this.locator = locator;
    }

    void checkValid() throws IncorrectSchemaException, SAXException {
      if (!validator.isValidSoFar())
        throw new IncorrectSchemaException();
      for (Enumeration enum = modeMap.keys(); enum.hasMoreElements();) {
        String modeName = (String)enum.nextElement();
        Mode mode = (Mode)modeMap.get(modeName);
        if (!mode.defined && !modeName.equals(DEFAULT_MODE_NAME))
          error("undefined_mode", modeName, mode.whereDefined);
      }
      if (hadError)
        throw new IncorrectSchemaException();
    }

    public void startElement(String uri, String localName,
                             String qName, Attributes attributes)
            throws SAXException {
      super.startElement(uri, localName, qName, attributes);
      xmlBaseHandler.startElement();
      String xmlBase = attributes.getValue(WellKnownNamespaces.XML, "base");
      if (xmlBase != null)
        xmlBaseHandler.xmlBaseAttribute(xmlBase);
      if (!MNS_URI.equals(uri) || foreignDepth > 0) {
        foreignDepth++;
        return;
      }
      if (!validator.isValidSoFar())
        return;
      if (localName.equals("rules")) {
        String modeName = attributes.getValue("", "startMode");
        if (modeName == null)
          modeName = DEFAULT_MODE_NAME;
        startMode = lookupCreateMode(modeName);
        return;
      }
      if (localName.equals("cover")) {
        String ns = attributes.getValue("", "ns");
        if (!Uri.isAbsolute(ns) && !ns.equals(""))
          error("ns_absolute");
        currentElementAction.covered.add(attributes.getValue("", "ns"));
        return;
      }
      String modesValue = attributes.getValue("", "modes");
      String[] modeNames;
      if (modesValue == null)
        modeNames = new String[] { DEFAULT_MODE_NAME };
      else
        modeNames = StringSplitter.split(modesValue);
      Mode[] modes = new Mode[modeNames.length];
      for (int i = 0; i < modes.length; i++) {
        modes[i] = lookupCreateMode(modeNames[i]);
        modes[i].defined = true;
      }
      if (localName.equals("strict") || localName.equals("lax")) {
        boolean strict = localName.equals("strict");
        for (int i = 0; i < modes.length; i++) {
          if (modes[i].strictDefined)
            error("strict_multiply_defined", modeNames[i]);
          else {
            modes[i].strict = strict;
            modes[i].strictDefined = true;
          }
        }
        return;
      }
      boolean isAttribute = localName.equals("validateAttributes");
      String ns = attributes.getValue("", "ns");
      if (!Uri.isAbsolute(ns) && (isAttribute || !ns.equals("")))
        error("ns_absolute");
      String schemaUri = attributes.getValue("", "schema");
      if (Uri.hasFragmentId(schemaUri))
        error("schema_fragment_id");
      schemaUri = Uri.resolve(xmlBaseHandler.getBaseUri(),
                              Uri.escapeDisallowedChars(schemaUri));
      try {
        if (isAttribute) {
          Schema schema = factory.createChildSchema(wrapAttributesSchema(schemaUri));
          for (int i = 0; i < modes.length; i++) {
            if (modes[i].attributesMap.get(ns) != null)
              error("validate_attributes_multiply_defined", modeNames[i], ns);
            else
              modes[i].attributesMap.put(ns, schema);
          }
        }
        else {
          Schema schema = factory.createChildSchema(new InputSource(schemaUri));
          String modeName = attributes.getValue("", "useMode");
          if (modeName == null)
            modeName = DEFAULT_MODE_NAME;
          Mode mode = lookupCreateMode(modeName);
          if (mode.whereDefined == null && locator != null)
            mode.whereDefined = new LocatorImpl(locator);
          String prune = attributes.getValue("", "prune");
          currentElementAction = new ElementAction(schema,
                                                   mode,
                                                   prune != null && prune.trim().equals("true"));
          for (int i = 0; i < modes.length; i++) {
            if (modes[i].elementMap.get(ns) != null)
              error("validate_element_multiply_defined", modeNames[i], ns);
            else
              modes[i].elementMap.put(ns, currentElementAction);
          }
        }
      }
      catch (IncorrectSchemaException e) {
        hadError = true;
      }
      catch (IOException e) {
        throw new WrappedIOException(e);
      }
    }

    public void endElement(String namespaceURI, String localName,
                           String qName)
            throws SAXException {
      super.endElement(namespaceURI, localName, qName);
      xmlBaseHandler.endElement();
      if (foreignDepth > 0)
        foreignDepth--;
    }

    void error(String key) throws SAXException {
      hadError = true;
      if (eh == null)
        return;
      eh.error(new SAXParseException(localizer.message(key), locator));
    }

    void error(String key, String arg) throws SAXException {
      hadError = true;
      if (eh == null)
        return;
      eh.error(new SAXParseException(localizer.message(key, arg), locator));
    }

    void error(String key, String arg, Locator locator) throws SAXException {
      hadError = true;
      if (eh == null)
        return;
      eh.error(new SAXParseException(localizer.message(key, arg), locator));
    }

    void error(String key, String arg1, String arg2) throws SAXException {
      hadError = true;
      if (eh == null)
        return;
      eh.error(new SAXParseException(localizer.message(key, arg1, arg2), locator));
    }

  }

  SchemaImpl(InputSource in, MnsSchemaFactory factory)
          throws IOException, SAXException, IncorrectSchemaException {
    XMLReader xr = factory.getXMLReaderCreator().createXMLReader();
    ErrorHandler eh = factory.getErrorHandler();
    Handler h = new Handler(factory, factory.getMnsSchema().createValidator(eh), eh);
    xr.setContentHandler(h);
    xr.setErrorHandler(eh);
    try {
      xr.parse(in);
    }
    catch (WrappedIOException e) {
      throw e.getException();
    }
    catch (SAXException e) {
      if (e.getException() instanceof WrappedIOException)
        throw ((WrappedIOException)e.getException()).getException();
      else
        throw e;
    }
    h.checkValid();
  }

  public ValidatorHandler createValidator(ErrorHandler eh) {
    return new ValidatorHandlerImpl(startMode, eh);
  }

  public ValidatorHandler createValidator() {
    return createValidator(null);
  }

  private static InputSource wrapAttributesSchema(String attributesSchemaUri) {
    StringBuffer buf = new StringBuffer();
    buf.append("<element name=\"");
    buf.append(BEARER_PREFIX);
    buf.append(':');
    buf.append(BEARER_LOCAL_NAME);
    buf.append('"');
    buf.append(" xmlns=\"");
    buf.append(WellKnownNamespaces.RELAX_NG);
    buf.append('"');
    buf.append(" xmlns:");
    buf.append(BEARER_PREFIX);
    buf.append("=\"");
    buf.append(BEARER_URI);
    buf.append("\"><externalRef href=\"");
    buf.append(attributesSchemaUri);
    buf.append("\"/></element>");
    return new InputSource(new StringReader(buf.toString()));
  }

  private Mode lookupCreateMode(String name) {
    Mode mode = (Mode)modeMap.get(name);
    if (mode == null) {
      mode = new Mode();
      modeMap.put(name, mode);
    }
    return mode;
  }
}