package edu.georgetown.uis.corpling.pepper.streusle;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import org.corpus_tools.pepper.common.DOCUMENT_STATUS;
import org.corpus_tools.pepper.impl.PepperMapperImpl;
import org.corpus_tools.salt.SALT_TYPE;
import org.corpus_tools.salt.SaltFactory;
import org.corpus_tools.salt.common.*;
import org.eclipse.emf.common.util.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonValue;


public class StreusleMapper extends PepperMapperImpl {
    private static final Logger logger = LoggerFactory.getLogger(StreusleImporter.class);

    private STextualDS buildTextualDS(SDocumentGraph doc, List<JsonObject> sentences) {
        StringBuilder sentenceText = new StringBuilder();
        for (JsonObject sentence : sentences) {
            JsonValue tObj = sentence.get("text");
            assert tObj.isString();
            sentenceText.append(tObj.asString());
            sentenceText.append("\t"); // TODO: Is there a better character to separate sentences?
        }
        return doc.createTextualDS(sentenceText.toString());
    }

    private List<SToken> tokenizeSentence(SDocumentGraph doc, STextualDS primaryText,
                                          String sentenceString, int sOffset, JsonArray tokList) {
        List<SToken> tokens = new ArrayList<>();
        int lastTokEndIndex = 0;
        for (JsonValue tokV : tokList.asArray()) {
            JsonObject tokObj = tokV.asObject();
            String tokenString = tokObj.get("word").asString();

            int beginIndex = sentenceString.indexOf(tokenString, lastTokEndIndex);
            assert beginIndex > -1;

            lastTokEndIndex = beginIndex + tokenString.length();
            SToken token = doc.createToken(primaryText, sOffset + beginIndex, sOffset + lastTokEndIndex);
            tokens.add(token);
        }

        return tokens;
    }

    private void processSentence(SDocumentGraph doc, STextualDS primaryText, JsonObject sentence) {
        // get the sentence text, e.g. "My 8 year old daughter loves this place."
        JsonValue sentenceStringV = sentence.get("text");
        String sentenceString = sentenceStringV.asString();

        // find where the sentence begins in the document
        int sOffset = primaryText.getText().indexOf(sentenceString);
        assert sOffset > -1;

        // get the list of token dicts, e.g. [{"#": 1, "word": "My", ...}, {"#": 2, "word": "8", ...}, ...]
        JsonArray tokList = sentence.get("toks").asArray();

        // make the SToken objects by looping over the array--we'll take care of annotations in other loops
        // would maybe be marginally more performant to process annotations all in one loop, but I will
        // prioritize clarity of code over performance
        List<SToken> tokens = tokenizeSentence(doc, primaryText, sentenceString, sOffset, tokList);

    }

    private void processDocument(SDocumentGraph doc, JsonValue jsonRoot) throws StreusleProcessingException {
        // cast each sentence into a JsonObject and keep them in a list, we'll need to loop over them
        List<JsonObject> sentences = new ArrayList<>();
        for (JsonValue sentenceValue : jsonRoot.asArray()) {
            JsonObject sentence = sentenceValue.asObject();
            sentences.add(sentence);
        }

        // make the STextualDS
        STextualDS primaryText = buildTextualDS(doc, sentences);

        // process each sentence independently
        for (JsonObject sentence : sentences) {
            processSentence(doc, primaryText, sentence);
        }
    }

    @Override
    public DOCUMENT_STATUS mapSDocument() {
        // parse the JSON
        URI resource = getResourceURI();
        logger.debug("Importing the file {}.", resource);
        JsonValue json;
        try {
            json = Json.parse(new FileReader(resource.toFileString()));
        } catch (IOException e) {
            return DOCUMENT_STATUS.FAILED;
        }

        // init state of doc
        SDocument d = getDocument();
        SDocumentGraph dg = SaltFactory.createSDocumentGraph();
        d.setDocumentGraph(dg);

        try {
            processDocument(dg, json);
        } catch (StreusleProcessingException e) {
            return DOCUMENT_STATUS.FAILED;
        }

        return DOCUMENT_STATUS.COMPLETED;

        ///**
        // * STEP 1: we create the primary data and hold a reference on the
        // * primary data object
        // */
        //STextualDS primaryText = getDocument().getDocumentGraph().createTextualDS("Is this example more complicated than it appears to be?");

        //// we add a progress to notify the user about the process status
        //// (this is very helpful, especially for longer taking processes)
        //addProgress(0.16);

        ///**
        // * STEP 2: we create a tokenization over the primary data
        // */
        //SToken tok_is = getDocument().getDocumentGraph().createToken(primaryText, 0, 2); // Is
        //SToken tok_thi = getDocument().getDocumentGraph().createToken(primaryText, 3, 7); // this
        //SToken tok_exa = getDocument().getDocumentGraph().createToken(primaryText, 8, 15); // example
        //SToken tok_mor = getDocument().getDocumentGraph().createToken(primaryText, 16, 20); // more
        //SToken tok_com = getDocument().getDocumentGraph().createToken(primaryText, 21, 32); // complicated
        //SToken tok_tha = getDocument().getDocumentGraph().createToken(primaryText, 33, 37); // than
        //SToken tok_it = getDocument().getDocumentGraph().createToken(primaryText, 38, 40); // it
        //SToken tok_app = getDocument().getDocumentGraph().createToken(primaryText, 41, 48); // appears
        //SToken tok_to = getDocument().getDocumentGraph().createToken(primaryText, 49, 51); // to
        //SToken tok_be = getDocument().getDocumentGraph().createToken(primaryText, 52, 54); // be
        //SToken tok_PUN = getDocument().getDocumentGraph().createToken(primaryText, 54, 55); // ?

        //// we add a progress to notify the user about the process status
        //// (this is very helpful, especially for longer taking processes)
        //addProgress(0.16);

        ///**
        // * STEP 3: we create a part-of-speech and a lemma annotation for
        // * tokens
        // */
        //// we create part-of-speech annotations
        //tok_is.createAnnotation(null, "pos", "VBZ");
        //tok_thi.createAnnotation(null, "pos", "DT");
        //tok_exa.createAnnotation(null, "pos", "NN");
        //tok_mor.createAnnotation(null, "pos", "RBR");
        //tok_com.createAnnotation(null, "pos", "JJ");
        //tok_tha.createAnnotation(null, "pos", "IN");
        //tok_it.createAnnotation(null, "pos", "PRP");
        //tok_app.createAnnotation(null, "pos", "VBZ");
        //tok_to.createAnnotation(null, "pos", "TO");
        //tok_be.createAnnotation(null, "pos", "VB");
        //tok_PUN.createAnnotation(null, "pos", ".");

        //// we create lemma annotations
        //tok_is.createAnnotation(null, "lemma", "be");
        //tok_thi.createAnnotation(null, "lemma", "this");
        //tok_exa.createAnnotation(null, "lemma", "example");
        //tok_mor.createAnnotation(null, "lemma", "more");
        //tok_com.createAnnotation(null, "lemma", "complicated");
        //tok_tha.createAnnotation(null, "lemma", "than");
        //tok_it.createAnnotation(null, "lemma", "it");
        //tok_app.createAnnotation(null, "lemma", "appear");
        //tok_to.createAnnotation(null, "lemma", "to");
        //tok_be.createAnnotation(null, "lemma", "be");
        //tok_PUN.createAnnotation(null, "lemma", ".");

        //// we add a progress to notify the user about the process status
        //// (this is very helpful, especially for longer taking processes)
        //addProgress(0.16);

        ///**
        // * STEP 4: we create some information structure annotations via
        // * spans, spans can be used, to group tokens to a set, which can be
        // * annotated
        // * <table border="1">
        // * <tr>
        // * <td>contrast-focus</td>
        // * <td colspan="9">topic</td>
        // * </tr>
        // * <tr>
        // * <td>Is</td>
        // * <td>this</td>
        // * <td>example</td>
        // * <td>more</td>
        // * <td>complicated</td>
        // * <td>than</td>
        // * <td>it</td>
        // * <td>appears</td>
        // * <td>to</td>
        // * <td>be</td>
        // * </tr>
        // * </table>
        // */
        //SSpan contrastFocus = getDocument().getDocumentGraph().createSpan(tok_is);
        //contrastFocus.createAnnotation(null, "Inf-Struct", "contrast-focus");
        //List<SToken> topic_set = new ArrayList<SToken>();
        //topic_set.add(tok_thi);
        //topic_set.add(tok_exa);
        //topic_set.add(tok_mor);
        //topic_set.add(tok_com);
        //topic_set.add(tok_tha);
        //topic_set.add(tok_it);
        //topic_set.add(tok_app);
        //topic_set.add(tok_to);
        //topic_set.add(tok_be);
        //SSpan topic = getDocument().getDocumentGraph().createSpan(topic_set);
        //topic.createAnnotation(null, "Inf-Struct", "topic");

        //// we add a progress to notify the user about the process status
        //// (this is very helpful, especially for longer taking processes)
        //addProgress(0.16);

        ///**
        // * STEP 5: we create anaphoric relation between 'it' and 'this
        // * example', therefore 'this example' must be added to a span. This
        // * makes use of the graph based model of Salt. First we create a
        // * relation, than we set its source and its target node and last we
        // * add the relation to the graph.
        // */
        //List<SToken> target_set = new ArrayList<SToken>();
        //target_set.add(tok_thi);
        //target_set.add(tok_exa);
        //SSpan target = getDocument().getDocumentGraph().createSpan(target_set);
        //SPointingRelation anaphoricRel = SaltFactory.createSPointingRelation();
        //anaphoricRel.setSource(tok_is);
        //anaphoricRel.setTarget(target);
        //anaphoricRel.setType("anaphoric");
        //// we add the created relation to the graph
        //getDocument().getDocumentGraph().addRelation(anaphoricRel);

        //// we add a progress to notify the user about the process status
        //// (this is very helpful, especially for longer taking processes)
        //addProgress(0.16);

        ///**
        // * STEP 6: We create a syntax tree following the Tiger scheme
        // */
        //SStructure root = SaltFactory.createSStructure();
        //SStructure sq = SaltFactory.createSStructure();
        //SStructure np1 = SaltFactory.createSStructure();
        //SStructure adjp1 = SaltFactory.createSStructure();
        //SStructure adjp2 = SaltFactory.createSStructure();
        //SStructure sbar = SaltFactory.createSStructure();
        //SStructure s1 = SaltFactory.createSStructure();
        //SStructure np2 = SaltFactory.createSStructure();
        //SStructure vp1 = SaltFactory.createSStructure();
        //SStructure s2 = SaltFactory.createSStructure();
        //SStructure vp2 = SaltFactory.createSStructure();
        //SStructure vp3 = SaltFactory.createSStructure();

        //// we add annotations to each SStructure node
        //root.createAnnotation(null, "cat", "ROOT");
        //sq.createAnnotation(null, "cat", "SQ");
        //np1.createAnnotation(null, "cat", "NP");
        //adjp1.createAnnotation(null, "cat", "ADJP");
        //adjp2.createAnnotation(null, "cat", "ADJP");
        //sbar.createAnnotation(null, "cat", "SBAR");
        //s1.createAnnotation(null, "cat", "S");
        //np2.createAnnotation(null, "cat", "NP");
        //vp1.createAnnotation(null, "cat", "VP");
        //s2.createAnnotation(null, "cat", "S");
        //vp2.createAnnotation(null, "cat", "VP");
        //vp3.createAnnotation(null, "cat", "VP");

        //// we add the root node first
        //getDocument().getDocumentGraph().addNode(root);
        //SALT_TYPE domRel = SALT_TYPE.SDOMINANCE_RELATION;
        //// than we add the rest and connect them to each other
        //getDocument().getDocumentGraph().addNode(root, sq, domRel);
        //getDocument().getDocumentGraph().addNode(sq, tok_is, domRel); // "Is"
        //getDocument().getDocumentGraph().addNode(sq, np1, domRel);
        //getDocument().getDocumentGraph().addNode(np1, tok_thi, domRel); // "this"
        //getDocument().getDocumentGraph().addNode(np1, tok_exa, domRel); // "example"
        //getDocument().getDocumentGraph().addNode(sq, adjp1, domRel);
        //getDocument().getDocumentGraph().addNode(adjp1, adjp2, domRel);
        //getDocument().getDocumentGraph().addNode(adjp2, tok_mor, domRel); // "more"
        //getDocument().getDocumentGraph().addNode(adjp2, tok_com, domRel); // "complicated"
        //getDocument().getDocumentGraph().addNode(adjp1, sbar, domRel);
        //getDocument().getDocumentGraph().addNode(sbar, tok_tha, domRel); // "than"
        //getDocument().getDocumentGraph().addNode(sbar, s1, domRel);
        //getDocument().getDocumentGraph().addNode(s1, np2, domRel);
        //getDocument().getDocumentGraph().addNode(np2, tok_it, domRel); // "it"
        //getDocument().getDocumentGraph().addNode(s1, vp1, domRel);
        //getDocument().getDocumentGraph().addNode(vp1, tok_app, domRel); // "appears"
        //getDocument().getDocumentGraph().addNode(vp1, s2, domRel);
        //getDocument().getDocumentGraph().addNode(s2, vp2, domRel);
        //getDocument().getDocumentGraph().addNode(vp2, tok_to, domRel); // "to"
        //getDocument().getDocumentGraph().addNode(vp2, vp3, domRel);
        //getDocument().getDocumentGraph().addNode(vp3, tok_be, domRel); // "be"
        //getDocument().getDocumentGraph().addNode(root, tok_PUN, domRel); // "?"

        //// we set progress to 'done' to notify the user about the process
        //// status (this is very helpful, especially for longer taking
        //// processes)
        //setProgress(1.0);

        //// now we are done and return the status that everything was
        //// successful
        //return (DOCUMENT_STATUS.COMPLETED);
    }

    private class StreusleProcessingException extends Exception {

    }
}

