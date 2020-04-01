package edu.georgetown.uis.corpling.pepper.streusle;

import org.corpus_tools.pepper.impl.PepperImporterImpl;
import org.corpus_tools.pepper.modules.PepperImporter;
import org.corpus_tools.pepper.modules.PepperMapper;
import org.corpus_tools.salt.graph.Identifier;
import org.eclipse.emf.common.util.URI;
import org.osgi.service.component.annotations.Component;

/**
 * @author Luke Gessler
 * An importer for <a href="https://github.com/nert-nlp/streusle">STREUSLE</a>
 * <a href="https://github.com/nert-nlp/streusle/blob/master/CONLLULEX.md">.conllulex</a>
 * files.
 */
@Component(name = "StreusleImporterComponent", factory = "PepperImporterComponentFactory")
public class StreusleImporter extends PepperImporterImpl implements PepperImporter{
	public static final String NAME = "StreusleImporter";
	public static final String FORMAT_NAME = "json";
	public static final String FORMAT_VERSION = "1.0";
	public StreusleImporter() {
		super();
		setName(NAME);
		setSupplierContact(URI.createURI("corpora@georgetown.edu"));
		setSupplierHomepage(URI.createURI("https://corpling.uis.georgetown.edu/corpling/"));
		setDesc("Imports the 9 extra columns in the STREUSLE format (https://github.com/nert-nlp/streusle/).");
		this.addSupportedFormat(FORMAT_NAME, FORMAT_VERSION,
				URI.createURI("https://github.com/nert-nlp/streusle/blob/master/CONLLULEX.md"));
		getDocumentEndings().add("json");
	}

	public PepperMapper createPepperMapper(Identifier Identifier) {
		StreusleMapper mapper = new StreusleMapper();
		mapper.setResourceURI(getIdentifier2ResourceTable().get(Identifier));
		return (mapper);
	}
}
