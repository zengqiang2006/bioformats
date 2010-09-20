//
// ScanrReader.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.in;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Hashtable;
import java.util.Vector;

import loci.common.ByteArrayHandle;
import loci.common.DataTools;
import loci.common.Location;
import loci.common.RandomAccessInputStream;
import loci.common.xml.XMLTools;
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.FormatReader;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.MetadataStore;
import loci.formats.tiff.IFD;
import loci.formats.tiff.TiffParser;

import ome.xml.model.primitives.NonNegativeInteger;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * ScanrReader is the file format reader for Olympus ScanR datasets.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://dev.loci.wisc.edu/trac/java/browser/trunk/components/bio-formats/src/loci/formats/in/ScanrReader.java">Trac</a>,
 * <a href="http://dev.loci.wisc.edu/svn/java/trunk/components/bio-formats/src/loci/formats/in/ScanrReader.java">SVN</a></dd></dl>
 *
 * @author Melissa Linkert melissa at glencoesoftware.com
 */
public class ScanrReader extends FormatReader {

  // -- Constants --

  private static final String XML_FILE = "experiment_descriptor.xml";
  private static final String EXPERIMENT_FILE = "experiment_descriptor.dat";
  private static final String ACQUISITION_FILE = "AcquisitionLog.dat";
  private static final String[] METADATA_SUFFIXES = new String[] {"dat", "xml"};

  // -- Fields --

  private Vector<String> metadataFiles = new Vector<String>();
  private int wellRows, wellColumns;
  private int fieldRows, fieldColumns;
  private int wellCount = 0;
  private Vector<String> channelNames = new Vector<String>();
  private Hashtable<String, Integer> wellLabels =
    new Hashtable<String, Integer>();
  private Hashtable<Integer, Integer> wellNumbers =
    new Hashtable<Integer, Integer>();
  private String plateName;
  private Double pixelSize;

  private String[] tiffs;
  private MinimalTiffReader reader;

  // -- Constructor --

  /** Constructs a new ScanR reader. */
  public ScanrReader() {
    super("Olympus ScanR", new String[] {"dat", "xml", "tif"});
    domains = new String[] {FormatTools.HCS_DOMAIN};
    suffixSufficient = false;
  }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isSingleFile(String) */
  public boolean isSingleFile(String id) throws FormatException, IOException {
    Location file = new Location(id).getAbsoluteFile();
    String name = file.getName();
    if (name.equals(XML_FILE) || name.equals(EXPERIMENT_FILE) ||
      name.equals(ACQUISITION_FILE))
    {
      return true;
    }
    Location parent = file.getParentFile();
    if (parent != null) {
      parent = parent.getParentFile();
    }
    return new Location(parent, XML_FILE).exists();
  }

  /* @see loci.formats.IFormatReader#fileGroupOption(String) */
  public int fileGroupOption(String id) throws FormatException, IOException {
    return FormatTools.MUST_GROUP;
  }

  /* @see loci.formats.IFormatReader#isThisType(String, boolean) */
  public boolean isThisType(String name, boolean open) {
    String localName = new Location(name).getName();
    if (localName.equals(XML_FILE) || localName.equals(EXPERIMENT_FILE) ||
      localName.equals(ACQUISITION_FILE))
    {
      return true;
    }

    return super.isThisType(name, open);
  }

  /* @see loci.formats.IFormatReader#isThisType(RandomAccessInputStream) */
  public boolean isThisType(RandomAccessInputStream stream) throws IOException {
    TiffParser p = new TiffParser(stream);
    IFD ifd = p.getFirstIFD();
    if (ifd == null) return false;

    Object s = ifd.getIFDValue(IFD.SOFTWARE);
    if (s == null) return false;
    String software = s instanceof String[] ? ((String[]) s)[0] : s.toString();
    return software.trim().equals("National Instruments IMAQ");
  }

  /* @see loci.formats.IFormatReader#getSeriesUsedFiles(boolean) */
  public String[] getSeriesUsedFiles(boolean noPixels) {
    FormatTools.assertId(currentId, true, 1);

    Vector<String> files = new Vector<String>();
    for (String file : metadataFiles) {
      if (file != null) files.add(file);
    }

    if (!noPixels && tiffs != null) {
      int offset = getSeries() * getImageCount();
      for (int i=0; i<getImageCount(); i++) {
        if (tiffs[offset + i] != null) {
          files.add(tiffs[offset + i]);
        }
      }
    }

    return files.toArray(new String[files.size()]);
  }

  /* @see loci.formats.IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws IOException {
    super.close(fileOnly);
    if (!fileOnly) {
      if (reader != null) {
        reader.close();
      }
      reader = null;
      tiffs = null;
      plateName = null;
      channelNames.clear();
      fieldRows = fieldColumns = 0;
      wellRows = wellColumns = 0;
      metadataFiles.clear();
      wellLabels.clear();
      wellNumbers.clear();
      wellCount = 0;
      pixelSize = null;
    }
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.checkPlaneParameters(this, no, buf.length, x, y, w, h);

    int index = getSeries() * getImageCount() + no;
    if (tiffs[index] != null) {
      reader.setId(tiffs[index]);
      reader.openBytes(0, buf, x, y, w, h);
      reader.close();

      // mask out the sign bit
      ByteArrayHandle pixels = new ByteArrayHandle(buf);
      pixels.setOrder(
        isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
      for (int i=0; i<buf.length; i+=2) {
        pixels.seek(i);
        short value = pixels.readShort();
        value = (short) (value & 0xfff);
        pixels.seek(i);
        pixels.writeShort(value);
      }
      buf = pixels.getBytes();
      pixels.close();
    }

    return buf;
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    super.initFile(id);
    if (metadataFiles.size() > 0) {
      // this dataset has already been initialized
      return;
    }

    // make sure we have the .xml file
    if (!checkSuffix(id, "xml") && isGroupFiles()) {
      Location parent = new Location(id).getAbsoluteFile().getParentFile();
      if (checkSuffix(id, "tif")) {
        parent = parent.getParentFile();
      }
      String[] list = parent.list();
      for (String file : list) {
        if (file.equals(XML_FILE)) {
          id = new Location(parent, file).getAbsolutePath();
          super.initFile(id);
          break;
        }
      }
      if (!checkSuffix(id, "xml")) {
        throw new FormatException("Could not find " + XML_FILE + " in " +
          parent.getAbsolutePath());
      }
    }
    else if (!isGroupFiles() && checkSuffix(id, "tif")) {
      TiffReader r = new TiffReader();
      r.setMetadataStore(getMetadataStore());
      r.setId(id);
      core = r.getCoreMetadata();
      metadataStore = r.getMetadataStore();

      Hashtable globalMetadata = r.getGlobalMetadata();
      for (Object key : globalMetadata.keySet()) {
        addGlobalMeta(key.toString(), globalMetadata.get(key));
      }

      r.close();
      tiffs = new String[] {id};
      reader = new MinimalTiffReader();

      return;
    }

    Location dir = new Location(id).getAbsoluteFile().getParentFile();
    String[] list = dir.list(true);

    for (String file : list) {
      Location f = new Location(dir, file);
      if (!f.isDirectory() && checkSuffix(file, METADATA_SUFFIXES)) {
        metadataFiles.add(f.getAbsolutePath());
      }
    }

    // parse XML metadata

    String xml = DataTools.readFile(id).trim();

    // add the appropriate encoding, as some ScanR XML files use non-UTF8
    // characters without specifying an encoding

    if (xml.startsWith("<?")) {
      xml = xml.substring(xml.indexOf("?>") + 2);
    }
    xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + xml;

    XMLTools.parseXML(xml, new ScanrHandler());

    Vector<String> uniqueRows = new Vector<String>();
    Vector<String> uniqueColumns = new Vector<String>();

    for (String well : wellLabels.keySet()) {
      if (!Character.isLetter(well.charAt(0))) continue;
      String row = well.substring(0, 1).trim();
      String column = well.substring(1).trim();
      if (!uniqueRows.contains(row) && row.length() > 0) uniqueRows.add(row);
      if (!uniqueColumns.contains(column) && column.length() > 0) {
        uniqueColumns.add(column);
      }
    }

    wellRows = uniqueRows.size();
    wellColumns = uniqueColumns.size();

    if (wellRows * wellColumns != wellCount) {
      adjustWellDimensions();
    }

    int nChannels = getSizeC() == 0 ? channelNames.size() : getSizeC();
    if (nChannels == 0) nChannels = 1;
    int nSlices = getSizeZ() == 0 ? 1 : getSizeZ();
    int nTimepoints = getSizeT();
    int nWells = wellCount;
    int nPos = fieldRows * fieldColumns;
    if (nPos == 0) nPos = 1;

    // get list of TIFF files

    Location dataDir = new Location(dir, "data");
    list = dataDir.list(true);
    if (list == null) {
      // try to find the TIFFs in the current directory
      list = dir.list(true);
    }
    else dir = dataDir;
    if (nTimepoints == 0 ||
      list.length < nTimepoints * nChannels * nSlices * nWells * nPos)
    {
      nTimepoints = list.length / (nChannels * nWells * nPos * nSlices);
      if (nTimepoints == 0) nTimepoints = 1;
    }

    tiffs = new String[nChannels * nWells * nPos * nTimepoints * nSlices];

    int next = 0;
    String[] keys = wellLabels.keySet().toArray(new String[wellLabels.size()]);
    int realPosCount = 0;
    for (int well=0; well<nWells; well++) {
      int wellIndex = wellNumbers.get(well);
      String wellPos = getBlock(wellIndex, "W");
      int originalIndex = next;

      for (int pos=0; pos<nPos; pos++) {
        String posPos = getBlock(pos + 1, "P");
        int posIndex = next;

        for (int z=0; z<nSlices; z++) {
          String zPos = getBlock(z, "Z");

          for (int t=0; t<nTimepoints; t++) {
            String tPos = getBlock(t, "T");

            for (int c=0; c<nChannels; c++) {
              for (String file : list) {
                if (file.indexOf(wellPos) != -1 && file.indexOf(zPos) != -1 &&
                  file.indexOf(posPos) != -1 && file.indexOf(tPos) != -1 &&
                  file.indexOf(channelNames.get(c)) != -1)
                {
                  tiffs[next++] = new Location(dir, file).getAbsolutePath();
                  break;
                }
              }
            }
          }
        }
        if (posIndex != next) realPosCount++;
      }
      if (next == originalIndex && well < keys.length) {
        wellLabels.remove(keys[well]);
      }
    }

    if (wellLabels.size() > 0 && wellLabels.size() != nWells) {
      uniqueRows.clear();
      uniqueColumns.clear();
      for (String well : wellLabels.keySet()) {
        if (!Character.isLetter(well.charAt(0))) continue;
        String row = well.substring(0, 1).trim();
        String column = well.substring(1).trim();
        if (!uniqueRows.contains(row) && row.length() > 0) uniqueRows.add(row);
        if (!uniqueColumns.contains(column) && column.length() > 0) {
          uniqueColumns.add(column);
        }
      }

      nWells = uniqueRows.size() * uniqueColumns.size();
      adjustWellDimensions();
    }
    if (realPosCount < nPos) {
      nPos = realPosCount;
    }

    reader = new MinimalTiffReader();
    reader.setId(tiffs[0]);
    int sizeX = reader.getSizeX();
    int sizeY = reader.getSizeY();
    int pixelType = reader.getPixelType();

    // we strongly suspect that ScanR incorrectly records the
    // signedness of the pixels

    switch (pixelType) {
      case FormatTools.INT8:
        pixelType = FormatTools.UINT8;
        break;
      case FormatTools.INT16:
        pixelType = FormatTools.UINT16;
        break;
    }

    boolean rgb = reader.isRGB();
    boolean interleaved = reader.isInterleaved();
    boolean indexed = reader.isIndexed();
    boolean littleEndian = reader.isLittleEndian();

    reader.close();

    core = new CoreMetadata[nWells * nPos];
    for (int i=0; i<getSeriesCount(); i++) {
      core[i] = new CoreMetadata();
      core[i].sizeC = nChannels;
      core[i].sizeZ = nSlices;
      core[i].sizeT = nTimepoints;
      core[i].sizeX = sizeX;
      core[i].sizeY = sizeY;
      core[i].pixelType = pixelType;
      core[i].rgb = rgb;
      core[i].interleaved = interleaved;
      core[i].indexed = indexed;
      core[i].littleEndian = littleEndian;
      core[i].dimensionOrder = "XYCTZ";
      core[i].imageCount = nSlices * nTimepoints * nChannels;
      core[i].bitsPerPixel = 12;
    }

    MetadataStore store = makeFilterMetadata();
    MetadataTools.populatePixels(store, this);

    store.setPlateID(MetadataTools.createLSID("Plate", 0), 0);

    int nFields = fieldRows * fieldColumns;

    for (int i=0; i<getSeriesCount(); i++) {
      MetadataTools.setDefaultCreationDate(store, id, i);

      int field = i % nFields;
      int well = i / nFields;
      int wellIndex = wellNumbers.get(well) - 1;

      int wellRow = wellIndex / wellColumns;
      int wellCol = wellIndex % wellColumns;

      store.setWellID(MetadataTools.createLSID("Well", 0, well), 0, well);
      store.setWellColumn(new NonNegativeInteger(wellCol), 0, well);
      store.setWellRow(new NonNegativeInteger(wellRow), 0, well);

      String wellSample =
        MetadataTools.createLSID("WellSample", 0, well, field);
      store.setWellSampleID(wellSample, 0, well, field);
      store.setWellSampleIndex(new NonNegativeInteger(i), 0, well, field);
      String imageID = MetadataTools.createLSID("Image", i);
      store.setWellSampleImageRef(imageID, 0, well, field);
      store.setImageID(imageID, i);

      String name = "Well " + (wellIndex + 1) + ", Field " + (field + 1) +
        " (Spot " + (i + 1) + ")";
      store.setImageName(name, i);
    }

    if (getMetadataOptions().getMetadataLevel() != MetadataLevel.MINIMUM) {
      // populate LogicalChannel data

      for (int i=0; i<getSeriesCount(); i++) {
        for (int c=0; c<getSizeC(); c++) {
          store.setChannelName(channelNames.get(c), i, c);
        }
        if (pixelSize != null) {
          store.setPixelsPhysicalSizeX(pixelSize, i);
          store.setPixelsPhysicalSizeY(pixelSize, i);
        }
      }

      String row = wellRows > 26 ? "Number" : "Letter";
      String col = wellRows > 26 ? "Letter" : "Number";

      store.setPlateRowNamingConvention(getNamingConvention(row), 0);
      store.setPlateColumnNamingConvention(getNamingConvention(col), 0);
      store.setPlateName(plateName, 0);
    }
  }

  // -- Helper class --

  class ScanrHandler extends DefaultHandler {
    private String key, value;
    private String qName;

    private String wellIndex;

    // -- DefaultHandler API methods --

    public void characters(char[] ch, int start, int length) {
      String v = new String(ch, start, length);
      if (v.trim().length() == 0) return;
      if (qName.equals("Name")) {
        key = v;
      }
      else if (qName.equals("Val")) {
        value = v.trim();
        addGlobalMeta(key, value);

        if (key.equals("columns/well")) {
          fieldColumns = Integer.parseInt(value);
        }
        else if (key.equals("rows/well")) {
          fieldRows = Integer.parseInt(value);
        }
        else if (key.equals("# slices")) {
          core[0].sizeZ = Integer.parseInt(value);
        }
        else if (key.equals("timeloop real")) {
          core[0].sizeT = Integer.parseInt(value);
        }
        else if (key.equals("timeloop count")) {
          core[0].sizeT = Integer.parseInt(value) + 1;
        }
        else if (key.equals("name")) {
          channelNames.add(value);
        }
        else if (key.equals("plate name")) {
          plateName = value;
        }
        else if (key.equals("idle")) {
          int lastIndex = channelNames.size() - 1;
          if (value.equals("0") &&
            !channelNames.get(lastIndex).equals("Autofocus"))
          {
            core[0].sizeC++;
          }
          else channelNames.remove(lastIndex);
        }
        else if (key.equals("well selection table + cDNA")) {
          if (Character.isDigit(value.charAt(0))) {
            wellIndex = value;
            wellNumbers.put(new Integer(wellCount), new Integer(value));
            wellCount++;
          }
          else {
            wellLabels.put(value, new Integer(wellIndex));
          }
        }
        else if (key.equals("conversion factor um/pixel")) {
          pixelSize = new Double(value);
        }
      }
    }

    public void startElement(String uri, String localName, String qName,
      Attributes attributes)
    {
      this.qName = qName;
    }

  }

  // -- Helper methods --

  private String getBlock(int index, String axis) {
    String b = String.valueOf(index);
    while (b.length() < 5) b = "0" + b;
    return axis + b;
  }

  private void adjustWellDimensions() {
    if (wellCount <= 8) {
      wellColumns = 2;
      wellRows = 4;
    }
    else if (wellCount <= 96) {
      wellColumns = 12;
      wellRows = 8;
    }
    else if (wellCount <= 384) {
      wellColumns = 24;
      wellRows = 16;
    }
  }

}
