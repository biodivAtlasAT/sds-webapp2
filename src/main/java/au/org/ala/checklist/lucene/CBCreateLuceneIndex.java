package au.org.ala.checklist.lucene;

import au.org.ala.data.util.RankType;
import java.io.BufferedReader;
import java.io.File;

import java.io.InputStreamReader;
import java.util.HashSet;

import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.apache.commons.io.LineIterator;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.analysis.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriter.MaxFieldLength;

import org.apache.lucene.store.FSDirectory;
import org.gbif.ecat.model.ParsedName;
import org.gbif.ecat.parser.NameParser;
import org.gbif.file.CSVReader;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.gbif.portal.util.taxonomy.TaxonNameSoundEx;

/**
 * Creates the Lucene index based on the cb_names export generated by ChecklistBankExporter
 * 
 * @author Natasha
 */
public class CBCreateLuceneIndex {

    private String cbExportFile = "cb_name_usages.txt";
    private String lexFile = "cb_lex_names.txt";
    private String irmngFile = "irmng_classification.txt";
    private String colFile = "col_common_names.txt";
    private String anbgFile = "anbg_common_names.txt";
    protected Log log = LogFactory.getLog(CBCreateLuceneIndex.class);
    protected ApplicationContext context;
    protected DataSource dataSource;
    protected JdbcTemplate dTemplate;
    //the position in the line for each of the required values
    //nub id\tparent nub id\tlsid\tsynonym id\tsynonym lsid\tname id\tcanonical name\tauthor\tportal rank id\trank\tlft\trgt\tkingdom id\tkingdom\tphylum id\tphylum\tclass id\tclass\torder id \torder\tfamily id\tfamily\tgenus id\tgenus\tspecies id\tspecies
    private final int POS_ID = 0;
    private final int POS_LSID = 2;
    private final int POS_SYN_ID = 3;
    private final int POS_SYN_LSID = 4;
    private final int POS_NAME_ID = 5;
    private final int POS_NAME = 6;
    private final int POS_RANK_ID = 8;
    private final int POS_RANK = 9;
    private final int POS_KID = 12;
    private final int POS_K = 13;
    private final int POS_PID = 14;
    private final int POS_P = 15;
    private final int POS_CID = 16;
    private final int POS_C = 17;
    private final int POS_OID = 18;
    private final int POS_O = 19;
    private final int POS_FID = 20;
    private final int POS_F = 21;
    private final int POS_GID = 22;
    private final int POS_G = 23;
    private final int POS_SID = 24;
    private final int POS_S = 25;

    //Fields that are being indexed or stored in the lucene index
    public enum IndexField {

        NAME("name"),
        NAMES("names"),
        CLASS("class"),
        ID("id"),
        RANK("rank"),
        SEARCHABLE_NAME("searchcan"),
        LSID("lsid"),
        KINGDOM("kingdom"),
        HOMONYM("homonym"),
        SYNONYM("synonym"),
        PHYLUM("phylum"),
        GENUS("genus"),
        COMMON_NAME("common");

        String name;

        IndexField(String name) {
            this.name = name;
        }

        public String toString() {
            return name;
        }
    };
    NameParser parser = new NameParser();
    Set<String> knownHomonyms = new HashSet<String>();
    // SQL used to get all the names that are part of the same lexical group
    // private String namesSQL =
    // "select distinct scientific_name as name from name_in_lexical_group nlg JOIN name_string ns ON nlg.name_fk = ns.id JOIN name_usage nu ON nu.lexical_group_fk = nlg.lexical_group_fk where nu.name_fk =?";
    
    private TaxonNameSoundEx tnse;

    public void init() throws Exception {
        String[] locations = {"classpath*:au/org/ala/**/applicationContext-cb*.xml"};
        context = new ClassPathXmlApplicationContext(locations);
        dataSource = (DataSource) context.getBean("cbDataSource");
        dTemplate = new JdbcTemplate(dataSource);
        tnse = new TaxonNameSoundEx();
        // init the known homonyms
        LineIterator lines = new LineIterator(new BufferedReader(
                new InputStreamReader(
                this.getClass().getClassLoader().getResource(
                "au/org/ala/propertystore/known_homonyms.txt").openStream(), "ISO-8859-1")));
        while (lines.hasNext()) {
            String line = lines.nextLine().trim();
            knownHomonyms.add(line.toUpperCase());
        }
    }

    /**
     * Creates the index from the specified checklist bank names usage export file into
     * the specified index directory.
     * 
     * @param cbExportFile A cb export file as generated from the ChecklistBankExporter
     * @param lexFile
     * @param irmngFile
     * @param indexDir The directory in which the 2 indices will be created.
     * @throws Exception
     */
    public void createIndex(String exportsDir, String indexDir) throws Exception {

        KeywordAnalyzer analyzer = new KeywordAnalyzer();
        //Checklist Bank Main Index
        IndexWriter iw1 = new IndexWriter(FSDirectory.open(new File(indexDir + File.separator + "cb")), analyzer, true, MaxFieldLength.UNLIMITED);
        indexCB(iw1, exportsDir + File.separator + cbExportFile, exportsDir + File.separator + lexFile);
        //IRMNG index to aid in the resolving of homonyms
        IndexWriter iw2 = new IndexWriter(FSDirectory.open(new File(indexDir + File.separator + "irmng")), analyzer, true, MaxFieldLength.UNLIMITED);
        indexIRMNG(iw2, exportsDir + File.separator + irmngFile);
        //vernacular index to search for common names
        IndexWriter iw3 = new IndexWriter(FSDirectory.open(new File(indexDir + File.separator + "vernacular")), analyzer, true, MaxFieldLength.UNLIMITED);
        indexCommonNames(iw3, exportsDir + File.separator + colFile, exportsDir+File.separator + anbgFile);

    }

    private void indexCB(IndexWriter iw, String cbExportFile, String lexFile) throws Exception {
        long time = System.currentTimeMillis();
        CSVReader cbreader = CSVReader.buildReader(new File(cbExportFile), 1);

        CSVReader lexreader = CSVReader.buildReader(new File(lexFile), 0);

        String[] lexName = lexreader.readNext();
        int unprocessed = 0, records = 0;
        for (String[] values = cbreader.readNext(); values != null; values = cbreader.readNext()) {
            //process each line in the file


            if (values.length >= 26) {

                String classification = values[POS_KID] + "|" + values[POS_PID] + "|" + values[POS_CID] + "|" + values[POS_OID] + "|" + values[POS_FID] + "|" + values[POS_GID] + "|" + values[POS_SID];
                String lsid = values[POS_LSID];
                String id = values[POS_ID];
                String synonymValues = StringUtils.isEmpty(values[POS_SYN_ID]) ? null : values[POS_SYN_ID] + "\t" + values[POS_SYN_LSID];

                //determine whether or not the record represents an australian source
                //for now this will be determined using the lsid prefix in the future we may need to move to a more sophisticated method
                float boost = 1.0f;
                if (lsid.startsWith("urn:lsid:biodiversity.org.au")) {
                    boost = 2.0f;
                }

                Document doc = buildDocument(values[POS_NAME], classification, id, lsid, values[POS_RANK_ID], values[POS_RANK], values[POS_K], values[POS_P], values[POS_G], boost, synonymValues);//buildDocument(rec.value("http://rs.tdwg.org/dwc/terms/ScientificName"), classification, id, lsid, rec.value("rankID"), rec.value("http://rs.tdwg.org/dwc/terms/TaxonRank"), rec.value("http://rs.tdwg.org/dwc/terms/kingdom"), rec.value("http://rs.tdwg.org/dwc/terms/phylum"), rec.value("http://rs.tdwg.org/dwc/terms/genus"), boost, synonymValues);

                //Add the alternate names (these are the names that belong to the same lexical group)
                TreeSet<String> altNames = new TreeSet<String>();//store a unique set of all the possible alternative names

                while (lexName != null && Integer.parseInt(lexName[0]) <= Integer.parseInt(id)) {
                    if (lexName[0].equals(id)) {
                        //add the full name
                        altNames.add(lexName[1]);
                        ParsedName cn = parser.parseIgnoreAuthors(lexName[1]);
                        if(cn!=null && !cn.isHybridFormula()){
                            //add the canonical form
                            altNames.add(cn.buildCanonicalName());
                            
                        }
                        //addName(doc, lexName[1]);
                    }
                    lexName = lexreader.readNext();
                }
                if(altNames.size()>0){
                    //now add the names to the index
                    for(String name: altNames){
                        doc.add(new Field(IndexField.NAMES.toString(), name, Store.NO, Index.NOT_ANALYZED));
                    }
                }
               
                iw.addDocument(doc);
                records++;
                if (records % 100000 == 0) {
                    log.info("Processed " + records + " in " + (System.currentTimeMillis() - time) + " msecs (Total unprocessed: " + unprocessed + ")");
                }
            } else {
                //can't process line without all values

                unprocessed++;
            }
        }
        iw.commit();
        iw.optimize();
        iw.close();
        log.info("Lucene index created - processed a total of " + records + " records in " + (System.currentTimeMillis() - time) + " msecs (Total unprocessed: " + unprocessed + ")");
    }

    void indexIRMNG(IndexWriter iw, String irmngExport) throws Exception {
        log.info("Creating IRMNG index ...");
        File file = new File(irmngExport);
        if (file.exists()) {
            CSVReader reader = CSVReader.buildReader(file, 0);
            int count = 0;
            while (reader.hasNext()) {

                String[] values = reader.readNext();
                Document doc = new Document();
                if (values != null && values.length >= 11) {
                    doc.add(new Field(RankType.KINGDOM.getRank(), values[0], Store.YES, Index.NOT_ANALYZED));
                    doc.add(new Field(RankType.PHYLUM.getRank(), values[1], Store.YES, Index.NOT_ANALYZED));
                    doc.add(new Field(RankType.CLASS.getRank(), values[2], Store.YES, Index.NOT_ANALYZED));
                    doc.add(new Field(RankType.ORDER.getRank(), values[3], Store.YES, Index.NOT_ANALYZED));
                    doc.add(new Field(RankType.FAMILY.getRank(), values[4], Store.YES, Index.NOT_ANALYZED));
                    doc.add(new Field(RankType.GENUS.getRank(), values[5], Store.YES, Index.NOT_ANALYZED));
                    doc.add(new Field(IndexField.ID.toString(), values[6], Store.YES, Index.NOT_ANALYZED));//genus id
                    //            doc.add(new Field(, values[7], Store.YES, Index.NOT_ANALYZED));//synonym flag
                    doc.add(new Field(IndexField.SYNONYM.toString(), values[8], Store.YES, Index.NOT_ANALYZED));//synonym id
                    //            doc.add(new Field(,values[9], Store.YES, Index.NOT_ANALYZED)); //synonym name
                    doc.add(new Field(IndexField.HOMONYM.toString(), values[10], Store.YES, Index.NOT_ANALYZED)); //homonym flag
                    iw.addDocument(doc);
                    count++;
                }


            }
            iw.commit();
            iw.optimize();
            iw.close();
            log.info("Finished indexing " + count + " IRMNG taxa.");
        }
        else
            log.warn("Unable to create IRMNG index.  Can't locate " + irmngExport);
    }
    private void indexCommonNames(IndexWriter iw,String colFileName, String anbgFileName)throws Exception{
        log.info("Creating Common Names Index ...");

        java.util.HashMap<String, String> extraLsids = new java.util.HashMap<String, String>();


        File fileCol = new File(colFileName);
        if(fileCol.exists()){
            //build a mapping list of CoL LSIDs and the ANBG LSIDs that they map to
            CSVReader extraReader = CSVReader.buildReader(new File(fileCol.getParentFile(), "cb_identifiers.txt"), 0);
            while(extraReader.hasNext()){
                String[] values = extraReader.readNext();
                if(values != null && values.length >= 3){
                    if(!values[1].equals(""))
                        extraLsids.put(values[2], values[1]);
                }
            }
            
            CSVReader reader = CSVReader.buildReader(fileCol, 0);
            int count = 0;
            while (reader.hasNext()) {

                String[] values = reader.readNext();
                
                if(values != null && values.length >=4){
                    float boost = 1f;

                    //lookup the current Lsid to check if there is another default LSID
                    String newLsid = extraLsids.get(values[2]);
                    //give a boost to the Australian Common Names in CoL a smaller boost than that of the anbg records
                    if(values[3].equals("T"))
                        boost = 1.5f;
                    iw.addDocument(getCommonNameDocument(values, newLsid, boost));
                }
                count++;
            }
            //process the ANBG common names and add them to the same index

            log.info("Finished indexing " + count + " COL Common Names.");
        }
        else
            log.warn("Unable to index CoL Common Names.  Can't locate " + colFileName);
        File fileAnbg = new File(anbgFileName);
        if(fileAnbg.exists()){
            CSVReader reader = CSVReader.buildReader(fileAnbg,"UTF-8",'\t', '"' , 0);
            int count = 0;
            while (reader.hasNext()){
                String[] values = reader.readNext();
                if(values!= null && values.length>= 3){
                //all ANBG records should have the highest boost as they are our authoritive source
                iw.addDocument(getCommonNameDocument(values, null,2.0f));
                }
                count++;
            }
            log.info("Finished indexing " + count + " ANBG Common Names.");
        }
        else
            log.warn("Unable to index ANBG Common Names. Can't locate " + anbgFileName);
        
        iw.commit();
        iw.optimize();
        iw.close();
    }
    private Document getCommonNameDocument(String[] values, String newLsid, float boost){
        Document doc = new Document();
        //we are only interested in keeping all the alphanumerical values of the common name
        //when searching the same operations will need to be peformed on the search string
        doc.add(new Field(IndexField.COMMON_NAME.toString(), values[0].toUpperCase().replaceAll("[^A-Z0-9ÏËÖÜÄÉÈČÁÀÆŒ]", ""), Store.YES, Index.NOT_ANALYZED));
        doc.add(new Field(IndexField.NAME.toString(), values[1], Store.YES, Index.NOT_ANALYZED));
        newLsid = newLsid == null ? values[2]:newLsid;
        doc.add(new Field(IndexField.LSID.toString(), newLsid, Store.YES, Index.NO));
        doc.setBoost(boost);
        return doc;
    }
    
     private void addName(Document doc, String name) {
        doc.add(new Field(IndexField.NAMES.toString(), name, Store.NO, Index.NOT_ANALYZED));
        ParsedName cn = parser.parseIgnoreAuthors(name);
        //add the canonical form too (this uses a more generous name parser than the one that assigns the canonical form during the import process)
        if (cn != null) {
            String canName = cn.buildCanonicalName();
            doc.add(new Field(IndexField.NAMES.toString(), canName, Store.NO, Index.NOT_ANALYZED));
            //TODO should the alternate names add to the searchable canonical field?
            //doc.add(new Field(IndexField.SEARCHABLE_NAME.toString(),tnse.soundEx(name), Store.NO, Index.NOT_ANALYZED));

        }
    }

    /**
     * Builds and returns the initial document
     * @param key
     * @param value
     * @param id
     * @param rank
     * @param rankString
     * @return
     */
    private Document buildDocument(String name, String classification, String id, String lsid, String rank, String rankString, String kingdom, String phylum, String genus, float boost, String synonym) {
//        System.out.println("creating index " + name + " " + classification + " " + id + " " + lsid + " " + rank + " " + rankString+ " " + kingdom + " " + genus);
        Document doc = new Document();
        Field nameField = new Field(IndexField.NAME.toString(), name, Store.NO, Index.NOT_ANALYZED);
        nameField.setBoost(boost); //only want to apply the boost when searching on a name
        doc.add(nameField);
        doc.add(new Field(IndexField.ID.toString(), id, Store.YES, Index.NOT_ANALYZED));
        doc.add(new Field(IndexField.RANK.toString(), rank, Store.YES, Index.NOT_ANALYZED));
        doc.add(new Field(IndexField.RANK.toString(), rankString, Store.YES, Index.NOT_ANALYZED));

        doc.add(new Field(IndexField.LSID.toString(), lsid, Store.YES, Index.NOT_ANALYZED));//need to be able to search by LSID with a result from common names

        //add a search_canonical for the record
        doc.add(new Field(IndexField.SEARCHABLE_NAME.toString(), tnse.soundEx(name), Store.NO, Index.NOT_ANALYZED));
        if (synonym != null) {
            doc.add(new Field(IndexField.SYNONYM.toString(), synonym, Store.YES, Index.NO));
            //when the rank if genus or below use the Name Parser to get access to the correct Genus name to check for homonyms
            ParsedName pn = parser.parseIgnoreAuthors(name);
            try{
            if (rankString !=null &&RankType.getAllRanksBelow(RankType.GENUS.getId()).contains(RankType.getForName(rankString)) ) {
                if(pn != null)
                    genus = pn.getGenusOrAbove();
                else{
                    genus = null;
                    //System.out.println("Name: " + name + " pn: "+ pn + " rank: "+rankString);
                }
            } else {
                genus = null;
            }
            }
            catch(NullPointerException npe){
                System.out.println("Unknown rank : " + rankString);
            }

        } else {
            doc.add(new Field(IndexField.CLASS.toString(), classification, Store.YES, Index.NO));
            if (StringUtils.trimToNull(kingdom) != null) {
                doc.add(new Field(IndexField.KINGDOM.toString(), kingdom, Store.YES, Index.NOT_ANALYZED));
            }
            //        if(StringUtils.trimToNull(phylum) != null)
//            doc.add(new Field(IndexField.PHYLUM.toString(), phylum, Store.YES, Index.NOT_ANALYZED));
            if (StringUtils.trimToNull(genus) != null) {
                doc.add(new Field(IndexField.GENUS.toString(), genus, Store.NO, Index.NOT_ANALYZED));

            }
        }
        if (StringUtils.trimToNull(genus) != null && knownHomonyms.contains(genus.toUpperCase())) {
            doc.add(new Field(IndexField.HOMONYM.toString(), "T", Store.YES, Index.NOT_ANALYZED));
        }
        return doc;
    }

    /**
     * Generates the Lucene index required for the name matching API.
     * eg
     * au.org.ala.checklist.lucene.CBCreateLuceneIndex "/data/exports" "/data/lucene/namematching"
     *
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        CBCreateLuceneIndex indexer = new CBCreateLuceneIndex();
        indexer.init();
        if (args.length == 2) {
            indexer.createIndex(args[0], args[1]);
        } else {
            System.out.println("au.org.ala.checklist.lucene.CBCreateLuceneIndex <directory with export files> <directory in which to create indexes>");
           //indexer.createIndex("/data/exports/cb", "/data/lucene/namematching");
            
        }
    }
}
