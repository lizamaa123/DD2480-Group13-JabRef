package org.jabref.logic.importer;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;

import org.jabref.logic.importer.fetcher.TrustLevel;
import org.jabref.logic.util.URLUtil;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.StandardField;
import org.jabref.testutils.category.FetcherTest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.verify;

@FetcherTest
class FulltextFetchersTest {

    /// Required for testing the FulltextFetchers class.
    /// That code is not put to the FulltextFetcher class itself, because subclasses of FulltextFetcher should implement the getTrustLevel method.
    private interface FulltextFetcherWithTrustLevel extends FulltextFetcher {
        default TrustLevel getTrustLevel() {
            return TrustLevel.UNKNOWN;
        }
    }

    @Test
    void acceptPdfUrls() throws MalformedURLException {
        URL pdfUrl = URLUtil.create("http://docs.oasis-open.org/wsbpel/2.0/OS/wsbpel-v2.0-OS.pdf");
        FulltextFetcherWithTrustLevel finder = e -> Optional.of(pdfUrl);
        FulltextFetchers fetcher = new FulltextFetchers(Set.of(finder));
        assertEquals(Optional.of(pdfUrl), fetcher.findFullTextPDF(new BibEntry()));
    }

    @Test
    void rejectNonPdfUrls() throws MalformedURLException {
        URL pdfUrl = URLUtil.create("https://github.com/JabRef/jabref/blob/master/README.md");
        FulltextFetcherWithTrustLevel finder = e -> Optional.of(pdfUrl);
        FulltextFetchers fetcher = new FulltextFetchers(Set.of(finder));

        assertEquals(Optional.empty(), fetcher.findFullTextPDF(new BibEntry()));
    }

    @Test
    void noTrustLevel() throws MalformedURLException {
        URL pdfUrl = URLUtil.create("http://docs.oasis-open.org/wsbpel/2.0/OS/wsbpel-v2.0-OS.pdf");
        FulltextFetcherWithTrustLevel finder = e -> Optional.of(pdfUrl);
        FulltextFetchers fetcher = new FulltextFetchers(Set.of(finder));

        assertEquals(Optional.of(pdfUrl), fetcher.findFullTextPDF(new BibEntry()));
    }

    @Test
    void higherTrustLevelWins() throws IOException, FetcherException {
        // set an (arbitrary) DOI to the test entry to skip side effects inside the "findFullTextPDF" method
        BibEntry entry = new BibEntry().withField(StandardField.DOI, "10.5220/0007903201120130");

        FulltextFetcher finderHigh = mock(FulltextFetcher.class);
        when(finderHigh.getTrustLevel()).thenReturn(TrustLevel.SOURCE);
        final URL highUrl = URLUtil.create("http://docs.oasis-open.org/wsbpel/2.0/OS/wsbpel-v2.0-OS.pdf");
        when(finderHigh.findFullText(entry)).thenReturn(Optional.of(highUrl));

        FulltextFetcher finderLow = mock(FulltextFetcher.class);
        when(finderLow.getTrustLevel()).thenReturn(TrustLevel.UNKNOWN);
        final URL lowUrl = URLUtil.create("http://docs.oasis-open.org/opencsa/sca-bpel/sca-bpel-1.1-spec-cd-01.pdf");
        when(finderLow.findFullText(entry)).thenReturn(Optional.of(lowUrl));

        FulltextFetchers fetchers = new FulltextFetchers(Set.of(finderLow, finderHigh));

        assertEquals(Optional.of(highUrl), fetchers.findFullTextPDF(entry));
    }

    @Test
    void findFullTextByDOIDerivedFromTitle() throws Exception {
        // Entry with only a title,  no DOI, no file
        BibEntry entry = new BibEntry()
                .withField(StandardField.TITLE, "'To See or Not to See?' How Do Eye Movements Change Within Immersive Driving Environments");

        //dummy pdf
        URL expectedPdf = URLUtil.create("http://docs.oasis-open.org/wsbpel/2.0/OS/wsbpel-v2.0-OS.pdf");

        // Mock fetcher that only returns a PDF if the entry has a DOI
        // (proving CrossRef actually derived it)
        FulltextFetcher mockFetcher = mock(FulltextFetcher.class);
        when(mockFetcher.getTrustLevel()).thenReturn(TrustLevel.SOURCE);
        when(mockFetcher.findFullText(argThat(e ->
                e.getField(StandardField.DOI).isPresent()
        ))).thenReturn(Optional.of(expectedPdf));

        FulltextFetchers fetchers = new FulltextFetchers(Set.of(mockFetcher));
        Optional<URL> result = fetchers.findFullTextPDF(entry);

        //check if the URL is the same as expected
        assertEquals(Optional.of(expectedPdf), result);

        // Verify the exact DOI that was derived
        ArgumentCaptor<BibEntry> captor = ArgumentCaptor.forClass(BibEntry.class);
        verify(mockFetcher).findFullText(captor.capture());
        String derivedDoi = captor.getValue().getField(StandardField.DOI).orElse("");
        assertEquals("10.1145/2667239.2667268", derivedDoi.toLowerCase(Locale.ENGLISH));
    }
}
