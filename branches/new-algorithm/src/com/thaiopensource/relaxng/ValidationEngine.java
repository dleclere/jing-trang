package com.thaiopensource.relaxng;

import java.io.IOException;

import org.xml.sax.SAXException;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import org.relaxng.datatype.DatatypeLibraryFactory;

public class ValidationEngine {
  private XMLReaderCreator xrc;
  private XMLReader xr;
  private ErrorHandler eh;
  private DatatypeLibraryFactory dlf;
  private PatternBuilder pb;
  private Pattern p;
  private boolean checkId;
  private IdTypeMap idTypeMap;

  public void setXMLReaderCreator(XMLReaderCreator xrc) {
    this.xrc = xrc;
    if (eh != null)
      xr.setErrorHandler(eh);
  }
  
  /**
   * A call to setErrorHandler can be made at any time.
   */
  public void setErrorHandler(ErrorHandler eh) {
    this.eh = eh;
    if (xr != null)
      xr.setErrorHandler(eh);
  }

  public void setDatatypeLibraryFactory(DatatypeLibraryFactory dlf) {
    this.dlf = dlf;
  }

  public void setCheckId(boolean checkId) {
    this.checkId = checkId;
  }

  /**
   * setXMLReaderCreator must be called before any call to loadPattern
   */
  public boolean loadPattern(InputSource in) throws SAXException, IOException {
    pb = new PatternBuilder();
    xr = xrc.createXMLReader();
    xr.setErrorHandler(eh);
    p = null;
    p = PatternReader.readPattern(xrc, xr, pb, dlf, in);
    if (p == null)
      return false;
    idTypeMap = null;
    if (pb.hasIdTypes() && checkId)
      idTypeMap = new IdTypeMapBuilder(xr, p).getIdTypeMap();
    return true;
  }

  /**
   * loadPattern must be called before any call to validate
   */
  public boolean validate(InputSource in) throws SAXException, IOException {
    return validate1(new Validator(p, pb, xr), in);
  }

  /**
   * loadPattern must be called before any call to validateMultiThread
   * validateMultiThread can safely be called for a single
   * ValidationEngine from multiple threads simultaneously
   */
  public boolean validateMultiThread(InputSource in)
    throws SAXException, IOException {
    XMLReader xr = xrc.createXMLReader();
    Validator v = new Validator(p, new PatternBuilder(pb), xr);
    return validate1(v, in);
  }

  private boolean validate1(Validator v, InputSource in)
      throws SAXException, IOException {
    if (idTypeMap != null) {
      IdSoundnessChecker idSoundnessChecker = new IdSoundnessChecker(idTypeMap, xr);
      xr.setContentHandler(new SplitContentHandler(v, idSoundnessChecker));
      xr.parse(in);
      return v.getValid() && idSoundnessChecker.getSound();
    }
    else {
      xr.setContentHandler(v);
      xr.parse(in);
      return v.getValid();
    }
  }
}
