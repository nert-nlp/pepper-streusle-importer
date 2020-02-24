package edu.georgetown.uis.corpling.pepper.streusle;

import java.io.*;
import java.util.*;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import org.corpus_tools.pepper.common.DOCUMENT_STATUS;
import org.corpus_tools.pepper.impl.PepperMapperImpl;
import org.corpus_tools.salt.SaltFactory;
import org.corpus_tools.salt.common.*;
import org.corpus_tools.salt.core.SAnnotation;
import org.corpus_tools.salt.core.SLayer;
import org.corpus_tools.salt.core.SNode;
import org.corpus_tools.salt.core.SRelation;
import org.eclipse.emf.common.util.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonValue;


public class StreusleMapper extends PepperMapperImpl {
    private static final Logger logger = LoggerFactory.getLogger(StreusleImporter.class);

    private void annotateNode(SNode node, String key, String value) {
        SAnnotation ann = SaltFactory.createSAnnotation();
        ann.setName(key);
        ann.setValue(value);
        node.addAnnotation(ann);
    }

    /**
     * JSON doesn't provide us the unbroken text for the whole doc. We need to do that here
     * by getting each sentence's text and turning it into a tab-separated string.
     * Finally, we make a TextualDS for the whole thing and return it. This is the
     * foundation of our document-level SALT graph.
     */
    private STextualDS buildTextualDS(SDocumentGraph doc, List<JsonObject> sentences) {
        StringBuilder sentenceText = new StringBuilder();
        for (JsonObject sentence : sentences) {
            JsonValue tObj = sentence.get("text");
            sentenceText.append(tObj.asString());
            sentenceText.append(" ");
        }
        return doc.createTextualDS(sentenceText.toString());
    }

    /**
     * Goal: we now have a TextualDS for the whole document, and we need to make STokens for every token in the JSON.
     * Problem: the JSON tokens don't have the integral String offsets that we need in order to tell SALT where tokens
     *          begin and end
     * Solution: "chomp our way" up the sentence string finding one word at a time using indexOf, keeping track
     *           of how much of the string we've already chomped.
     * @param doc the document graph
     * @param tokens a JSON array of tokens, which are JSON objects
     * @param primaryText from buildTextualDS--the STextualDS we'll build our tokens on
     * @param sentenceString The literal string content of the sentence we're processing
     * @param sOffset where in STextualDS this sentence BEGINS. We need this because our STextualDS
     *                indexes apply for the whole document text, not just for this sentence
     * @return The tokens that were created for this sentence
     */
    private List<SToken> processWordField(SDocumentGraph doc, String sentenceId, List<JsonObject> tokens,
                                          STextualDS primaryText, String sentenceString, int sOffset) {
        List<SToken> sTokens = new ArrayList<>();
        int lastTokEndIndex = 0;
        int tokenId = 0;
        for (JsonObject token : tokens) {
            // what do we need to look for? simple, the next token in the sentence that hasn't been processed
            String tokenString = token.get("word").asString();

            // chomp chomp! only start looking from lastTokEndIndex
            int beginIndex = sentenceString.indexOf(tokenString, lastTokEndIndex);
            if (beginIndex < 0) {
                throw new UnsupportedOperationException(
                        "Couldn't find the token string `" + tokenString
                                + "` after index " + lastTokEndIndex
                                + " in sentence `" + sentenceString + "`"
                );
            }

            // great, we found the token we wanted to find, as expected. Add its length so we know where it ended
            lastTokEndIndex = beginIndex + tokenString.length();
            // create the token, being CAREFUL to add the sOffset to account for any sentences before this one
            SToken sToken = doc.createToken(primaryText, sOffset + beginIndex, sOffset + lastTokEndIndex);
            // give the token a name that lets us remember where it was
            sToken.setName(sentenceId + "_" + ++tokenId);
            sTokens.add(sToken);
        }

        return sTokens;
    }

    /**
     * Ellipsis tokens are stored in a separate key, "etoks", and so after we've processed regular tokens
     * we need to handle them as well. In addition to creating the token and annotating it for its ID,
     * we'll also need to insert (1) it and (2) its JsonObject into sTokens and tokens AND update the ID
     * to SToken index id2token. After this function, all other functions will think an ellipsis token
     * is just any other old token.
     */
    private void mergeEtoks(SDocumentGraph doc, String sentenceId,
                            List<SToken> sTokens, List<JsonObject> tokens, JsonObject sentence,
                            STextualDS primaryText, int sOffset, Map<String, SToken> id2token) {
        JsonArray eTokenArray = sentence.get("etoks").asArray();
        List<JsonObject> eTokens = new ArrayList<>();
        for (JsonValue eToken : eTokenArray) {
            eTokens.add(eToken.asObject());
        }

        for (JsonObject eTokenObject : eTokens) {
            JsonArray idArray = eTokenObject.get("#").asArray();
            // e.g. "10"
            String baseTokenId = Integer.toString(idArray.get(0).asInt());
            // e.g. "1"
            int eTokenCounter = idArray.get(1).asInt();
            // e.g. "10.1"
            String eTokenId = idArray.get(2).asString();

            // get a ref and index for the base token the etok appears after
            int baseTokenIndex = 0;
            while (!sTokens.get(baseTokenIndex)
                    .getAnnotation("conllu_id").getValue().equals(baseTokenId)) {
                baseTokenIndex++;
            }

            // insert the etok as a zero-width token and also set the conllu_id
            SToken eToken = doc.createToken(primaryText, sOffset, sOffset);
            eToken.setName(sentenceId + "_" + eTokenId);
            annotateNode(eToken, "conllu_id", eTokenId);
            id2token.put(eTokenId, eToken);

            // add them to our lists so that for the rest of processing they'll be treated as any other token
            sTokens.add(baseTokenIndex + eTokenCounter, eToken);
            tokens.add(baseTokenIndex + eTokenCounter, eTokenObject);
        }
    }

    /**
     * Annotate words with their lextag. We make a span because ANNIS's grid visualizer doesn't
     * mixing span annotations and token annotations.
     */
    private void processLextag(SDocumentGraph doc, List<SToken> sTokens, List<JsonObject> tokens) {
        for (int i = 0; i < sTokens.size(); i++) {
            JsonValue jsonValue = tokens.get(i).asObject().get("lextag");
            if (jsonValue == null || jsonValue.isNull()) {
                continue;
            }
            SToken sToken = sTokens.get(i);
            SSpan span = doc.createSpan(sToken);
            annotateNode(span, "lextag", jsonValue.asString());
        }
    }

    /**
     * Annotate single-word expressions with supersense and lexcat information.
     */
    private void processSwes(SDocumentGraph doc, JsonObject sentence, Map<String, SToken> id2token) {
        // a JsonObject mapping an ID like "1" to another JsonObject
        JsonObject swes = sentence.get("swes").asObject();

        // loop over keys
        for (String sweId: swes.names()) {
            JsonObject sweObj = swes.get(sweId).asObject();
            String ss = sweObj.get("ss").isNull() ? null : sweObj.get("ss").asString();
            String ss2 = sweObj.get("ss2").isNull() ? null : sweObj.get("ss2").asString();
            String lexcat = sweObj.get("lexcat").isNull() ? null : sweObj.get("lexcat").asString();
            for (JsonValue tokNum : sweObj.get("toknums").asArray()) {
                String tokId = Integer.toString(tokNum.asInt());
                SToken sToken = id2token.get(tokId);
                SSpan span = doc.createSpan(sToken);
                if (ss != null) {
                    annotateNode(span, "ss", ss);
                }
                if (ss2 != null) {
                    annotateNode(span, "ss2", ss2);
                }
                if (lexcat != null) {
                    annotateNode(span, "lexcat", lexcat);
                }
            }
        }
    }

    /**
     * Annotate strong or single multiword expressions with supersense and lexcat information.
     * We rely on the SSpans that were already created for MWEs, and we rely on the id2mwe map
     * to find them based on the ID we find under the "smwes" or "wmwes" keys.
     */
    private void processMwes(JsonObject sentence, Map<Integer, SSpan> id2mwe, boolean strong) {
        // a JsonObject mapping an ID like "1" to another JsonObject
        JsonObject mwes = sentence.get(strong ? "smwes" : "wmwes").asObject();

        // loop over keys
        for (String mweId: mwes.names()) {
            JsonObject mweObj = mwes.get(mweId).asObject();

            String ss = null, ss2 = null, lexcat = null;
            if (mweObj.get("ss") != null && !mweObj.get("ss").isNull()) {
                ss = mweObj.get("ss").asString();
            }
            if (mweObj.get("ss2") != null && !mweObj.get("ss2").isNull()) {
                ss2 = mweObj.get("ss2").asString();
            }
            if (mweObj.get("lexcat") != null && !mweObj.get("lexcat").isNull()) {
                lexcat = mweObj.get("lexcat").asString();
            }
            SSpan mweSpan = id2mwe.get(Integer.parseInt(mweId));
            if (ss != null) {
                annotateNode(mweSpan, "ss", ss);
            }
            if (ss2 != null) {
                annotateNode(mweSpan, "ss2", ss2);
            }
            if (lexcat != null) {
                annotateNode(mweSpan, "lexcat", lexcat);
            }
        }
    }


    /**
     * Many columns are simply an arbitrary string we associate with an SAnnotation. Use this function to handle them.
     */
    private void processSimpleStringField(List<SToken> sTokens, List<JsonObject> tokens,
                                          String jsonName, String annotationName) {
        for (int i = 0; i < sTokens.size(); i++) {
            JsonValue jsonValue = tokens.get(i).asObject().get(jsonName);
            if (jsonValue == null || jsonValue.isNull()) {
                continue;
            }
            SToken sToken = sTokens.get(i);
            annotateNode(sToken, annotationName, jsonValue.asString());
        }
    }
    /**
     * Annotates tokens with their CONLLU ID and also returns a map from CONLLU ID to SToken--useful for
     * adding dependencies later. Why is it Map<String and not Map<Integer? Because non-integral CONLLU
     * IDs are allowed: supertokens (e.g. 5-6) and ellipsis tokens (e.g. 5.1).
     * @return A map from 1-indexed CONLLU ID (e.g. 5) as a string to the SToken instance.
     */
    private Map<String, SToken> processIdField(List<SToken> sTokens, List<JsonObject> tokens) {
        Map<String, SToken> id2token = new HashMap<>();

        for (int i = 0; i < tokens.size(); i++) {
            JsonValue idVal = tokens.get(i).asObject().get("#");
            String id = Integer.toString(idVal.asInt());
            SToken sToken = sTokens.get(i);
            annotateNode(sToken, "conllu_id", id);
            id2token.put(id, sToken);
        }

        return id2token;
    }

    /**
     * Annotates the token for every feature that is defined on it, e.g. A=B, where
     * A will be the annotation's key and B will be the annotation's value.
     */
    private void processFeatsField(List<SToken> sTokens, List<JsonObject> tokens) {
        for (int i = 0; i < sTokens.size(); i++) {
            JsonValue jsonFeatsVal = tokens.get(i).asObject().get("feats");
            if (jsonFeatsVal == null || jsonFeatsVal.isNull()) {
                continue;
            }

            SToken sToken = sTokens.get(i);
            for (String feat : jsonFeatsVal.asString().split("\\|")) {
                String[] pieces = feat.split("=");
                annotateNode(sToken, pieces[0], pieces[1]);
            }
        }
    }

    /**
     * Add an SPointingRelation for each dependency. The root dependency is ignored by SALT convention.
     * @return a list of CONLLU ID strings, parallel to the sTokens list in index, that if non-null indicates
     *         the ID of the parent of a relation pointing to the sToken at that index, if there was any.
     *         We need this later to avoid duplicating relations.
     */
    private List<String> processHeadAndDeprelField(SDocumentGraph doc, String sentenceId, Map<String, SToken> id2token,
                                                    List<SToken> sTokens, List<JsonObject> tokens) {
        List<String> headIds = new ArrayList<>();
        for (int i = 0; i < sTokens.size(); i++) {
            JsonObject tokenObj = tokens.get(i).asObject();
            JsonValue jsonHeadVal = tokenObj.get("head");
            JsonValue jsonDeprelVal = tokenObj.get("deprel");
            if (jsonHeadVal == null || jsonHeadVal.isNull()
                    || jsonDeprelVal == null || jsonDeprelVal.isNull()
                    // root element, ignore because it is by convention not represented in SALT
                    || (jsonHeadVal.isNumber() && jsonHeadVal.asDouble() == 0)
            ) {
                headIds.add(null);
                continue;
            }

            String headIndex = Integer.toString(jsonHeadVal.asInt());
            SToken child = sTokens.get(i);
            SToken head = id2token.get(headIndex);

            // model a syntactic dependency as an SPointingRelation going from head to child
            SPointingRelation rel = SaltFactory.createSPointingRelation();
            rel.setType("ud");
            rel.setId(sentenceId + "_dep_" + headIndex + "-ud->" + i);
            rel.setSource(head);
            rel.setTarget(child);

            // annotate if not in edeps
            String edepsEquiv = Double.toString(jsonHeadVal.asDouble());
            if (edepsEquiv.endsWith(".0")) {
                edepsEquiv = edepsEquiv.substring(0, edepsEquiv.length() - 2);
            }
            edepsEquiv += ":" + jsonDeprelVal.asString();
            String edepsString = tokenObj.get("edeps").asString();
            if (!edepsString.contains(edepsEquiv)) {
                SAnnotation notInEdepsAnn = SaltFactory.createSAnnotation();
                notInEdepsAnn.setName("in_edeps");
                notInEdepsAnn.setValue("no");
                rel.addAnnotation(notInEdepsAnn);
            }

            // annotate the edge with deprel
            SAnnotation deprelAnn = SaltFactory.createSAnnotation();
            deprelAnn.setName("deprel");
            deprelAnn.setValue(jsonDeprelVal.asString());
            rel.addAnnotation(deprelAnn);
            doc.addRelation(rel);

            // keep track of the head we processed so we can only add any further heads later on
            headIds.add(Integer.toString(jsonHeadVal.asInt()));
        }
        return headIds;
    }

    /**
     * Recursive function that checks if there's a cycle between two nodes.
     * @param start The node we started from.
     * @param current The node we are currently traversing.
     * @return true iff a cycle exists between start and current
     */
    private boolean cycleCheck(SNode start, SNode current) {
        if (current == null) {
            return false;
        }
        for (SRelation childRel : current.getOutRelations()) {
            SNode next = (SNode) childRel.getTarget();
            if (next == start || cycleCheck(start, next)) {
                return true;
            }
        }
        return false;
    }

    // caution: this is called "edeps" in the JSON, but "DEPS" in the documentation
    /**
     * Handle enhanced dependencies. Careful, the JSON field name is "edeps", but the CONLLU spec
     * refers to this column as "DEPS". We avoid processing any dependencies that were already added.
     * @param doc
     * @param sentenceId
     * @param id2token CONLLU ID to SToken
     * @param sTokens SALT tokens
     * @param tokens JSON tokens
     * @param edepsLayer The layer containing the enhanced dependencies.
     * @param cycleLayer The layer containing the cycle-breaking edges in the edeps layer.
     * @param headIdsAlreadyProcessed A list of head IDs that will be used for each token to ignore a dependency
     *                                that has already been processed.
     */
    private void processDepsField(SDocumentGraph doc, String sentenceId, Map<String, SToken> id2token,
                                  List<SToken> sTokens, List<JsonObject> tokens, SLayer edepsLayer, SLayer cycleLayer,
                                  List<String> headIdsAlreadyProcessed) {
        for (int i = 0; i < sTokens.size(); i++) {
            JsonValue jsonDepsVal = tokens.get(i).asObject().get("edeps");
            if (jsonDepsVal == null || jsonDepsVal.isNull()) {
                continue;
            }

            SToken child = sTokens.get(i);
            for (String deps : jsonDepsVal.asString().split("\\|")) {
                String[] pieces = deps.split(":");

                // skip the dep if we've already processed it or it's a root node
                if (pieces[0].equals(headIdsAlreadyProcessed.get(i)) || pieces[0].equals("0")) {
                    continue;
                }
                SToken head = id2token.get(pieces[0]);

                SPointingRelation rel = SaltFactory.createSPointingRelation();
                // If we also made this type "ud", cycles could be introduced among all the
                // SPointingRelations representing "normal" dependencies and extended dependencies.
                // But SALT permits cycles so long as there is no cycle such that all major and
                // minor types on the edges in the cycle are all identical.
                // It is also for this reason that we need to check if introducing this edge
                // would create a cycle, and if so, break the cycle by changing edge type to
                // "udecycle".
                String edgeType = cycleCheck(head, child) ? "udecycle" : "ude";
                rel.setType(edgeType);
                rel.setId(sentenceId + "_extdep_" + pieces[0] + "-" + edgeType + "->" + i);
                rel.setSource(head);
                rel.setTarget(child);

                // annotate the edge with deprel
                SAnnotation deprelAnn = SaltFactory.createSAnnotation();
                deprelAnn.setName("deprel");
                // not as simple as pieces[1]: vals might contain colons like in 5:nmod:poss|...
                List<String> otherPieces = Arrays.asList(pieces).subList(1, pieces.length);
                String deprelVal = String.join(":", otherPieces);

                deprelAnn.setValue(deprelVal);
                rel.addAnnotation(deprelAnn);
                (edgeType.equals("udecycle") ? cycleLayer : edepsLayer).addRelation(rel);
            }
        }
    }

    /**
     * Just like FEATS: we add an annotation for each item in the MISC list.
     */
    private void processMiscField(List<SToken> sTokens, List<JsonObject> tokens) {
        for (int i = 0; i < sTokens.size(); i++) {
            JsonValue jsonMiscVal = tokens.get(i).asObject().get("misc");
            if (jsonMiscVal == null || jsonMiscVal.isNull()) {
                continue;
            }

            SToken sToken = sTokens.get(i);
            for (String misc : jsonMiscVal.asString().split("\\|")) {
                String[] pieces = misc.split("=");
                annotateNode(sToken, pieces[0], pieces[1]);
            }
        }
    }

    /**
     * For use with SMWE and WMWE fields. Creates an SSpan for every {S,W}MWE.
     * @param doc
     * @param sentenceId
     * @param sTokens
     * @param tokens
     * @param strong set to false if using for WMWE
     * @returns A mapping from String to MWE span, which we need to annotate later on
     */
    private Map<Integer, SSpan> processMWEField(SDocumentGraph doc, String sentenceId,
                                 List<SToken> sTokens, List<JsonObject> tokens, boolean strong) {
        // we'll return this at the end
        Map<Integer, SSpan> id2mwe = new HashMap<>();
        // maps strong multiword expression ID to list of token IDs that are a part of it, both 1-indexed.
        Map<Integer, List<Integer>> mwes = new HashMap<>();

        // populate the map by reading the json
        for (int i = 0; i < tokens.size(); i++) {
            JsonObject token = tokens.get(i);
            JsonValue mweVal = token.get(strong ? "smwe" : "wmwe");
            if (mweVal == null || mweVal.isNull() || !mweVal.isArray()) {
                continue;
            }

            // the second part of this array tells us the order of this word in the MWE, but this is useless
            // for us, so ignore it
            int mweId = mweVal.asArray().get(0).asInt();

            // prepare the list if this is the first time we've seen this mwe id
            if (!mwes.containsKey(mweId)) {
                mwes.put(mweId, new ArrayList<>());
            }
            mwes.get(mweId).add(i + 1);
        }

        // ensure that token IDs increase monotonically, as they ought to
        for (Integer mweId  : mwes.keySet()) {
            List<Integer> tokenIds = mwes.get(mweId);
            for (int i = 0; i < tokenIds.size() - 1; i++) {
                if (tokenIds.get(i) >= tokenIds.get(i + 1)) {
                    throw new RuntimeException((strong ? "SMWE " : "WMWE ") + mweId
                            + " in sentence " + sentenceId
                            + " has non-monotonically increasing token indexes.");
                }
            }
        }

        // turn the map into a bunch of spans
        for (int mweId : mwes.keySet()) {
            List<SToken> mweTokens = new ArrayList<>();
            for (int tokenId : mwes.get(mweId)) {
                // subtract 1 because tokenIds are 1-indexed
                mweTokens.add(sTokens.get(tokenId - 1));
            }

            SSpan mweSpan = doc.createSpan(mweTokens);
            mweSpan.setName(sentenceId + (strong ? "_SMWE_" : "_WMWE_") + mweId);
            id2mwe.put(mweId, mweSpan);
        }
        return id2mwe;
    }

    /**
     * Top-level function invoked for each sentence in the document. The "toks" property
     * in the `sentence` param contains an array of tokens, where each token is an object
     * where each property of that object corresponds to a CONLLULEX column. We will make
     * a separate function for each column to build up the sentence structure in a
     * programmatically decoupled way.
     * @param doc ref to the SDocumentGraph
     * @param edepsLayer The layer containing the enhanced dependencies.
     * @param cycleLayer The layer containing the cycle-breaking edges in the edeps layer.
     * @param primaryText the STextualDS object anchoring all our annotations
     * @param sentence the JSON piece corresponding to this sentence, which is a JSON object
     *                 with fields "sent_id", "streusle_sent_id", "mwe", "toks", "etoks",
     *                 "swes", "smwes", and "wmwes".
     */
    private void processSentence(SDocumentGraph doc, SLayer edepsLayer, SLayer cycleLayer,
                                 STextualDS primaryText, JsonObject sentence) {
        /*\
        |*| Setup
        \*/
        String sentenceId = sentence.get("sent_id").asString();
        // get the sentence text, e.g. "My 8 year old daughter loves this place."
        String sentenceString = sentence.get("text").asString();

        // find where the sentence begins in the document
        int sOffset = primaryText.getText().indexOf(sentenceString);
        if (sOffset < 0) {
            throw new UnsupportedOperationException(
                    "Couldn't find the sentence `" + sentenceString
                            + " in the document `" + primaryText.getText() + "`"
            );
        }

        // get the list of token dicts, e.g. [{"#": 1, "word": "My", ...}, {"#": 2, "word": "8", ...}, ...]
        JsonArray tokenArray = sentence.get("toks").asArray();
        List<JsonObject> tokens = new ArrayList<>();
        for (JsonValue token : tokenArray) {
            tokens.add(token.asObject());
        }

        /*\
        |*| Columns 1-2
        \*/
        // make the SToken objects by looping over the array--we'll take care of annotations in other loops
        // would maybe be marginally more performant to process annotations all in one loop, but I will
        // prioritize clarity of code over performance. This handles columns 1 (ID) and 2 (FORM).
        List<SToken> sTokens = processWordField(doc, sentenceId, tokens, primaryText, sentenceString, sOffset);
        Map<String, SToken> id2token = processIdField(sTokens, tokens);
        // ellipsis toks are stored separately in "etoks"--here, we get them into sTokens and tokens
        mergeEtoks(doc, sentenceId, sTokens, tokens, sentence, primaryText, sOffset, id2token);

        // with our final set of tokens, create a sentence span and annotate it with our ID
        SSpan sentenceSpan = doc.createSpan(sTokens);
        annotateNode(sentenceSpan, "sent_id", sentenceId);
        // consistency with the CONLL module: https://github.com/korpling/pepperModules-CoNLLModules/blob/154f84f0bd6cd6dd4bee8f066aad4d118b5cabe3/src/main/java/org/corpus_tools/peppermodules/conll/Conll2SaltMapper.java#L565
        annotateNode(sentenceSpan, "CAT", "S");

        /*\
        |*| Columns 3-10
        \*/
        // column 3, LEMMA
        processSimpleStringField(sTokens, tokens, "lemma", "lemma");
        // column 4, UPOS
        processSimpleStringField(sTokens, tokens, "upos", "upos");
        // column 5, XPOS
        processSimpleStringField(sTokens, tokens, "xpos", "pos");
        // column 6, FEATS
        processFeatsField(sTokens, tokens);
        // columns 7 and 8, HEAD and DEPREL
        List<String> headIdsAlreadyProcessed = processHeadAndDeprelField(doc, sentenceId, id2token, sTokens, tokens);
        // column 9, DEPS
        processDepsField(doc, sentenceId, id2token, sTokens, tokens, edepsLayer, cycleLayer, headIdsAlreadyProcessed);
        // column 10, MISC
        processMiscField(sTokens, tokens);

        /*\
        |*| Columns 11-19
        \*/
        // do nothing for column 17, currently not used
        // do nothing for column 13, LEXLEMMA, and column 18, WLEMMA: redundant with other info
        // SMWE (11), WMWE (16), and LEXTAG (19) are stored directly on "toks", handle them:
        Map<Integer, SSpan> id2smwe = processMWEField(doc, sentenceId, sTokens, tokens, true);
        Map<Integer, SSpan> id2wmwe = processMWEField(doc, sentenceId, sTokens, tokens, false);
        processLextag(doc, sTokens, tokens);

        // LEXCAT (12), SS (14), and SS2 (15) are stored separately under "mwes", "smwes", and "wmwes"
        processSwes(doc, sentence, id2token);
        processMwes(sentence, id2smwe, true);
        processMwes(sentence, id2wmwe, false);
    }

    /**
     * Top-level function for processing a STREUSLE JSON that sets up the STextualDS for the document
     * and kicks off processing of each sentence.
     * @param doc A reference to the SDocumentGraph
     * @param jsonRoot The root of the raw parsed JSON
     */
    private void processDocument(SDocumentGraph doc, JsonValue jsonRoot) {
        // note that throughout the com.eclipsesource.json package's API, a JsonValue is returned, and
        // we need to call an `.asXxxx` function to attempt to parse it and cast it as a subtype. If
        // this downcasting fails (e.g., we call .asArray() on a JsonValue that is actually a JsonString
        // instead of a JsonArray), then an UnsupportedOperationException will be thrown. This exception
        // is handled gracefully by Pepper, which will report a document conversion failure and display
        // the reason for the exception. For this reason, we do NOT need to check if a conversion is
        // valid first.

        // Cast each sentence into a JsonObject and keep them in a list, we'll need to loop over them.
        List<JsonObject> sentences = new ArrayList<>();
        for (JsonValue sentenceValue : jsonRoot.asArray()) {
            JsonObject sentence = sentenceValue.asObject();
            sentences.add(sentence);
        }

        // make the STextualDS
        STextualDS primaryText = buildTextualDS(doc, sentences);

        // make and hold on to a layer reference for enhanced dependencies: any relation
        SLayer edeps = SaltFactory.createSLayer();
        edeps.setName("edeps");
        SLayer cycle = SaltFactory.createSLayer();
        cycle.setName("cycle");

        // process each sentence independently
        for (JsonObject sentence : sentences) {
            processSentence(doc, edeps, cycle, primaryText, sentence);
        }

        // add the layer after we're done adding rels to it
        doc.addLayer(edeps);
        doc.addLayer(cycle);
    }

    /**
     * Takes <em>document-level</em> STREUSLE JSON's and turns them into SALT.
     */
    @Override
    public DOCUMENT_STATUS mapSDocument() {
        // Pepper tells us the URI of the document being processed
        URI resource = getResourceURI();
        logger.debug("Importing the file {}.", resource);

        // Attempt to parse the file at that URI as JSON
        JsonValue json;
        try {
            json = Json.parse(new FileReader(resource.toFileString()));
        } catch (IOException e) {
            return DOCUMENT_STATUS.FAILED;
        }

        // Pepper has already prepared an SDocument object. Grab it and init it
        SDocument d = getDocument();
        SDocumentGraph dg = SaltFactory.createSDocumentGraph();
        d.setDocumentGraph(dg);

        // Begin processing the JSON's contents
        processDocument(dg, json);
        return DOCUMENT_STATUS.COMPLETED;
    }
}

