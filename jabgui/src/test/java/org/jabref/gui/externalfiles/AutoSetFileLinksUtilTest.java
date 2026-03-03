package org.jabref.gui.externalfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;

import javafx.collections.FXCollections;

import org.jabref.gui.externalfiletype.ExternalFileType;
import org.jabref.gui.externalfiletype.ExternalFileTypes;
import org.jabref.gui.frame.ExternalApplicationsPreferences;
import org.jabref.logic.FilePreferences;
import org.jabref.logic.util.io.AutoLinkPreferences;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.types.StandardEntryType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoSetFileLinksUtilTest {

    private final ExternalApplicationsPreferences externalApplicationsPreferences = mock(ExternalApplicationsPreferences.class);
    private final FilePreferences filePreferences = mock(FilePreferences.class);
    private final AutoLinkPreferences autoLinkPrefs = mock(AutoLinkPreferences.class);
    private final BibDatabaseContext databaseContext = mock(BibDatabaseContext.class);
    private final BibEntry entry = new BibEntry(StandardEntryType.Article);
    private Path path = null;

    /// Modified version of AutoLinkFilesAction.linkFilesTask.onLinkedFilesUpdated.
    private final BiConsumer<List<LinkedFile>, BibEntry> onLinkedFilesUpdated = (newLinkedFiles, entry) -> {
        // Undo manager related code is removed

        // Directly update BibEntry model instead of doing it in UI thread by `UiTaskExecutor.runAndWaitInJavaFXThread`,
        // which is not properly set up and is out of the unit test scope
        entry.setFiles(newLinkedFiles);
    };

    @BeforeEach
    void setUp(@TempDir Path folder) throws IOException {
        path = folder.resolve("CiteKey.pdf");
        Files.createFile(path);
        entry.setCitationKey("CiteKey");
        when(externalApplicationsPreferences.getExternalFileTypes())
                .thenReturn(FXCollections.observableSet(new TreeSet<>(ExternalFileTypes.getDefaultExternalFileTypes())));

        when(autoLinkPrefs.getRegularExpression()).thenReturn("");
        when(autoLinkPrefs.getCitationKeyDependency()).thenReturn(AutoLinkPreferences.CitationKeyDependency.START);
        when(autoLinkPrefs.getKeywordSeparator()).thenReturn(';');
    }

    @Test
    void findAssociatedNotLinkedFilesSuccess() throws IOException {
        when(databaseContext.getFileDirectories(any())).thenReturn(List.of(path.getParent()));
        List<LinkedFile> expected = List.of(new LinkedFile("", Path.of("CiteKey.pdf"), "PDF"));
        AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext, externalApplicationsPreferences, filePreferences, autoLinkPrefs);
        Collection<LinkedFile> actual = util.findAssociatedNotLinkedFiles(entry);
        assertEquals(expected, actual);
    }

    @Test
    void findAssociatedNotLinkedFilesForEmptySearchDir() throws IOException {
        when(databaseContext.getFileDirectories(any())).thenReturn(List.of());
        when(filePreferences.shouldStoreFilesRelativeToBibFile()).thenReturn(false);
        AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext, externalApplicationsPreferences, filePreferences, autoLinkPrefs);
        Collection<LinkedFile> actual = util.findAssociatedNotLinkedFiles(entry);
        assertEquals(List.of(), actual);
    }

    @Test
    void findOneAssociatedNotLinkedFile(@TempDir Path tempDir) throws IOException {
        Path directory = tempDir.resolve("files");
        Path oldPath = directory.resolve("old/minimal.pdf");
        Files.createDirectories(oldPath.getParent());
        Files.createFile(oldPath);

        LinkedFile stale = new LinkedFile("", oldPath.toString(), "PDF");
        BibEntry entry = new BibEntry(StandardEntryType.Misc);
        entry.addFile(stale);

        String newFile = "new/minimal.pdf";
        Path newPath = directory.resolve(newFile);
        Files.createDirectories(newPath.getParent());
        Files.move(oldPath, newPath);

        BibDatabaseContext context = mock(BibDatabaseContext.class);
        when(context.getFileDirectories(filePreferences)).thenReturn(List.of(directory));

        AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(
                context,
                externalApplicationsPreferences,
                filePreferences,
                autoLinkPrefs);

        Collection<LinkedFile> matches = util.findAssociatedNotLinkedFiles(entry);

        assertEquals(1, matches.size());
        assertEquals(newFile,
                matches.stream().findFirst().map(LinkedFile::getLink).orElse(""));
    }

    @Test
    void findAllAssociatedNotLinkedFilesInsteadOfTheFirstOne(@TempDir Path tempDir) throws IOException {
        Path directory = tempDir.resolve("files");
        Path oldPath = directory.resolve("old/minimal.pdf");
        BibEntry entry = new BibEntry(StandardEntryType.Misc)
                .withFiles(List.of(new LinkedFile("", oldPath.toString(), "PDF")));

        // Simulate a file move
        String newPath1String = "new1/minimal.pdf";
        Path newPath1 = directory.resolve(newPath1String);
        Files.createDirectories(newPath1.getParent());
        Files.createFile(newPath1);

        // Create a second copy of the file
        String newPath2String = "new2/minimal.pdf";
        Path newPath2 = directory.resolve(newPath2String);
        Files.createDirectories(newPath2.getParent());
        Files.copy(newPath1, newPath2);

        BibDatabaseContext context = mock(BibDatabaseContext.class);
        when(context.getFileDirectories(filePreferences)).thenReturn(List.of(directory));

        AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(
                context,
                externalApplicationsPreferences,
                filePreferences,
                autoLinkPrefs);

        Collection<LinkedFile> matchedFiles = util.findAssociatedNotLinkedFiles(entry);
        Set<LinkedFile> expected = Set.of(
                new LinkedFile("", newPath1String, "PDF"),
                new LinkedFile("", newPath2String, "PDF"));
        assertEquals(expected, Set.copyOf(matchedFiles));
    }

    @Test
    void findAllAssociatedNotLinkedFilesAndNotRepeated(@TempDir Path tempDir) throws IOException {
        when(autoLinkPrefs.getCitationKeyDependency()).thenReturn(AutoLinkPreferences.CitationKeyDependency.START);

        // File and folder
        Path subdir = tempDir.resolve("subdir");
        Files.createDirectory(subdir);
        Path fileA = tempDir.resolve("CK_A.pdf");
        Files.createFile(fileA);
        Path fileB = tempDir.resolve("CK_B.pdf");
        Files.createFile(fileB);
        BibEntry entry = new BibEntry(StandardEntryType.Misc)
                .withFiles(List.of(
                        new LinkedFile("", "subdir/CK_B.pdf", "PDF")
                ));
        entry.setCitationKey("CK");

        BibDatabaseContext context = mock(BibDatabaseContext.class);
        when(context.getFileDirectories(filePreferences)).thenReturn(List.of(tempDir));
        AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(
                context,
                externalApplicationsPreferences,
                filePreferences,
                autoLinkPrefs);

        // find by citation will return CK_A.pdf and CK_B.pdf
        // find by broken linked file name will return CK_B.pdf
        Collection<LinkedFile> matchedFiles = util.findAssociatedNotLinkedFiles(entry);
        Set<LinkedFile> expected = Set.of(
                new LinkedFile("", "CK_A.pdf", "PDF"),
                new LinkedFile("", "CK_B.pdf", "PDF"));
        assertEquals(expected, Set.copyOf(matchedFiles));
    }


    @Nested
    @DisplayName("linkAssociatedFiles")
    class linkAssociatedFiles {

        @Nested
        @DisplayName("whenFileAlreadyExists")
        class whenFileAlreadyExists {

            /*
            * Test verifies that the auto-link logic in Jabref leaves existing and valid data alone.
             */
            @Test
            @DisplayName("doNotChangeLinkWhenFileExistsAtLinkedLocation")
            void doNotChangeLinkWhenFileExistsAtLinkedLocation(@TempDir Path root) throws Exception {
                // Mock a database to look for PDFS & create a file on the disk
                when(AutoSetFileLinksUtilTest.this.databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                String fileName = "TestFile.pdf";
                Path testFile = root.resolve(fileName);
                Files.createFile(testFile);

                // Create a bibliography entry, entry has link and file exists at that link
                BibEntry testEntry = new BibEntry(StandardEntryType.Article);
                testEntry.setCitationKey("Test2026");
                LinkedFile existingLink = new LinkedFile("Source", fileName, "PDF");
                testEntry.setFiles(Collections.singletonList(existingLink));

                AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext, externalApplicationsPreferences, filePreferences, autoLinkPrefs);

                // Verify that the JabRef didn't add a second copy of the same file or change path
                util.linkAssociatedFiles(List.of(testEntry), onLinkedFilesUpdated);

                assertEquals(1, testEntry.getFiles().size(), "Should still have exactly one file linked");
                assertEquals(fileName, testEntry.getFiles().get(0).getLink(), "The link path should not have changed");

                // Check files on the hard drive that should be linked to this entry, test file matches the entry's critera but is already linked.
                // Hence result should be empty, otherwise Jabref would suggest user to add a file they already have.
                Collection<LinkedFile> result = util.findAssociatedNotLinkedFiles(testEntry);

                assertEquals(0, result.size(), "Should not suggest a file that is already correctly linked");
            }
        }

        @Nested
        @DisplayName("byCitationKeyOnly")
        class byCitationKeyOnly {

            @Nested
            @DisplayName("configuredCitationKeyDependencyWithStart")
            class configuredCitationKeyDependencyWithStart {

                /// ```
                /// └──citationKeyxxx.pdf
                /// ```
                @Test
                void autoLinkByCitationKeyStartAtRootFolder(@TempDir Path root) throws Exception {
                    when(autoLinkPrefs.getCitationKeyDependency()).thenReturn(AutoLinkPreferences.CitationKeyDependency.START);
                    when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                    String citationKey = "thisIsACitationKey";

                    // File and folder before moving
                    String fileName = "%s_foobar.pdf".formatted(citationKey);
                    Path fileA = root.resolve(fileName);
                    Files.createFile(fileA);

                    // Setup BibEntry without file
                    BibEntry entryA = new BibEntry(StandardEntryType.Article);
                    entryA.setCitationKey(citationKey);
                    List<BibEntry> entries = List.of(entryA);

                    // Run auto-link
                    AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                            externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                    util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                    // Check auto-link result
                    List<LinkedFile> expect = List.of(new LinkedFile("", Path.of(fileName), "PDF"));
                    List<LinkedFile> actual = entryA.getFiles();
                    assertEquals(expect, actual);
                }

                /// ```
                /// └──A
                /// └──B
                /// └──citationKeyxxx.pdf
                /// ```
                @Test
                void autoLinkByCitationKeyStartAtSubFolder(@TempDir Path root) throws Exception {
                    when(autoLinkPrefs.getCitationKeyDependency()).thenReturn(AutoLinkPreferences.CitationKeyDependency.START);
                    when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                    String citationKey = "thisIsACitationKey";

                    // File and folder before moving
                    Path folderA = root.resolve("A");
                    Files.createDirectory(folderA);
                    Path folderB = folderA.resolve("B");
                    Files.createDirectory(folderB);
                    String fileName = "%s_foobar.pdf".formatted(citationKey);
                    Path fileA = folderB.resolve(fileName);
                    Files.createFile(fileA);

                    // Setup BibEntry without file
                    BibEntry entryA = new BibEntry(StandardEntryType.Article);
                    entryA.setCitationKey(citationKey);
                    List<BibEntry> entries = List.of(entryA);

                    // Run auto-link
                    AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                            externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                    util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                    // Check auto-link result
                    List<LinkedFile> expect = List.of(new LinkedFile("", Path.of("A/B/%s".formatted(fileName)), "PDF"));
                    List<LinkedFile> actual = entryA.getFiles();
                    assertEquals(expect, actual);
                }

                /// ```
                /// └──A
                ///    └── B
                ///        ├── citationKey.pdf
                ///        └── citationKeyxxx.pdf
                /// ```
                @Test
                void autoLinkByCitationKeyStartMatchingTwoFilesAtSubFolder(@TempDir Path root) throws Exception {
                    when(autoLinkPrefs.getCitationKeyDependency()).thenReturn(AutoLinkPreferences.CitationKeyDependency.START);
                    when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                    String citationKey = "thisIsACitationKey";

                    // File and folder before moving
                    Path folderA = root.resolve("A");
                    Files.createDirectory(folderA);
                    Path folderB = folderA.resolve("B");
                    Files.createDirectory(folderB);
                    String fileNameA = "%s_foobar.pdf".formatted(citationKey);
                    Path fileA = folderB.resolve(fileNameA);
                    Files.createFile(fileA);
                    String fileNameB = "%s.pdf".formatted(citationKey);
                    Path fileB = folderB.resolve(fileNameB);
                    Files.createFile(fileB);

                    // Setup BibEntry without file
                    BibEntry entryA = new BibEntry(StandardEntryType.Article);
                    entryA.setCitationKey(citationKey);
                    List<BibEntry> entries = List.of(entryA);

                    // Run auto-link
                    AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                            externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                    util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                    // Check auto-link result

                    List<LinkedFile> expect = List.of(
                            new LinkedFile("", Path.of("A/B/%s".formatted(fileNameA)), "PDF"),
                            new LinkedFile("", Path.of("A/B/%s".formatted(fileNameB)), "PDF")
                    );
                    List<LinkedFile> actual = entryA.getFiles();
                    assertEquals(Set.copyOf(expect), Set.copyOf(actual));
                }

                /// ```
                /// └── A
                ///    └── B
                ///        ├── citationKey.pdf
                ///        └── citationKeyxxx.pdf
                /// ```
                @Test
                void autoLinkByCitationKeyStartMatchingTwoFilesAtSubFolderAndOneMatchABrokenLinkedFileName(@TempDir Path root) throws Exception {
                    when(autoLinkPrefs.getCitationKeyDependency()).thenReturn(AutoLinkPreferences.CitationKeyDependency.START);
                    when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                    String citationKey = "thisIsACitationKey";

                    // File and folder before moving
                    Path folderA = root.resolve("A");
                    Files.createDirectory(folderA);
                    Path folderB = folderA.resolve("B");
                    Files.createDirectory(folderB);
                    String fileNameA = "%s_foobar.pdf".formatted(citationKey);
                    Path fileA = folderB.resolve(fileNameA);
                    Files.createFile(fileA);
                    String fileNameB = "%s.pdf".formatted(citationKey);
                    Path fileB = folderB.resolve(fileNameB);
                    Files.createFile(fileB);

                    // Setup BibEntry without file
                    BibEntry entryA = new BibEntry(StandardEntryType.Article);
                    entryA.setCitationKey(citationKey);
                    entryA.addFile(new LinkedFile("", fileNameB, "PDF"));
                    List<BibEntry> entries = List.of(entryA);

                    // Run auto-link
                    AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                            externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                    util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                    // Check auto-link result

                    List<LinkedFile> expect = List.of(
                            new LinkedFile("", Path.of("A/B/%s".formatted(fileNameA)), "PDF"),
                            new LinkedFile("", Path.of("A/B/%s".formatted(fileNameB)), "PDF")
                    );
                    List<LinkedFile> actual = entryA.getFiles();
                    assertEquals(Set.copyOf(expect), Set.copyOf(actual));
                }
            }

            @Nested
            @DisplayName("configuredCitationKeyDependencyWithExact")
            class configuredCitationKeyDependencyWithExact {

                /// ```
                /// └── citationKeyxxx.pdf
                /// ```
                @Test
                void autoLinkByCitationKeyExactAtRootFolderDoesNotWorkWithNotExactName(@TempDir Path root) throws Exception {
                    when(autoLinkPrefs.getCitationKeyDependency()).thenReturn(AutoLinkPreferences.CitationKeyDependency.EXACT);
                    when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                    String citationKey = "thisIsACitationKey";

                    // File and folder before moving
                    String fileName = "%s_foobar.pdf".formatted(citationKey);
                    Path fileA = root.resolve(fileName);
                    Files.createFile(fileA);

                    // Setup BibEntry without file
                    BibEntry entryA = new BibEntry(StandardEntryType.Article);
                    entryA.setCitationKey(citationKey);
                    List<BibEntry> entries = List.of(entryA);

                    // Run auto-link
                    AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                            externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                    util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                    // Check auto-link result
                    List<LinkedFile> expect = List.of();
                    List<LinkedFile> actual = entryA.getFiles();
                    assertEquals(expect, actual);
                }

                /// ```
                /// └── citationKey.pdf
                /// ```
                @Test
                void autoLinkByCitationKeyExactAtRootFolder(@TempDir Path root) throws Exception {
                    when(autoLinkPrefs.getCitationKeyDependency()).thenReturn(AutoLinkPreferences.CitationKeyDependency.EXACT);
                    when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                    String citationKey = "thisIsACitationKey";

                    // File and folder before moving
                    String fileName = "%s.pdf".formatted(citationKey);
                    Path fileA = root.resolve(fileName);
                    Files.createFile(fileA);

                    // Setup BibEntry without file
                    BibEntry entryA = new BibEntry(StandardEntryType.Article);
                    entryA.setCitationKey(citationKey);
                    List<BibEntry> entries = List.of(entryA);

                    // Run auto-link
                    AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                            externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                    util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                    // Check auto-link result
                    List<LinkedFile> expect = List.of(new LinkedFile("", Path.of(fileName), "PDF"));
                    List<LinkedFile> actual = entryA.getFiles();
                    assertEquals(expect, actual);
                }

                /// ```
                /// └── A
                ///    └── B
                ///        └── citationKey.pdf
                /// ```
                @Test
                void autoLinkByCitationKeyExactAtSubFolder(@TempDir Path root) throws Exception {
                    when(autoLinkPrefs.getCitationKeyDependency()).thenReturn(AutoLinkPreferences.CitationKeyDependency.EXACT);
                    when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                    String citationKey = "thisIsACitationKey";

                    // File and folder before moving
                    Path folderA = root.resolve("A");
                    Files.createDirectory(folderA);
                    Path folderB = folderA.resolve("B");
                    Files.createDirectory(folderB);
                    String fileName = "%s.pdf".formatted(citationKey);
                    Path fileA = folderB.resolve(fileName);
                    Files.createFile(fileA);

                    // Setup BibEntry without file
                    BibEntry entryA = new BibEntry(StandardEntryType.Article);
                    entryA.setCitationKey(citationKey);
                    List<BibEntry> entries = List.of(entryA);

                    // Run auto-link
                    AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                            externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                    util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                    // Check auto-link result
                    List<LinkedFile> expect = List.of(new LinkedFile("", Path.of("A/B/%s".formatted(fileName)), "PDF"));
                    List<LinkedFile> actual = entryA.getFiles();
                    assertEquals(expect, actual);
                }
            }

            // configuredCitationKeyDependencyWithRegex omitted
        }

        @Nested
        @DisplayName("byBrokenLinkedFileNameOnly")
        class byBrokenLinkedFileNameOnly {
            /// ```
            /// CK: WeDoNotCare
            /// └── broken_file_name.doc
            /// ```
            @Test
            void noAutoLinkByCitationKeyStartAtRootFolderWithSuffixMismatch(@TempDir Path root) throws Exception {
                when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                String citationKey = "WeDoNotCare";

                // File and folder
                String fileName = "broken_file_name.doc";
                Path fileA = root.resolve(fileName);
                Files.createFile(fileA);

                // Setup BibEntry with broken_file_name.pdf
                BibEntry entryA = new BibEntry(StandardEntryType.Article);
                entryA.addFile(new LinkedFile("", "broken_file_name.pdf", "PDF"));
                entryA.setCitationKey(citationKey);
                List<BibEntry> entries = List.of(entryA);

                // Run auto-link
                AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                        externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                // Check auto-link result
                // it should not be updated
                List<LinkedFile> expect = List.of(new LinkedFile("", "broken_file_name.pdf", "PDF"));
                List<LinkedFile> actual = entryA.getFiles();
                assertEquals(expect, actual);
            }

            /// ```
            /// CK: WeDoNotCare
            /// From
            /// ├── A
            /// └── A.pdf
            /// to
            /// └── A
            ///     └── A.pdf
            /// ```
            @Test
            void autoLinkMoveFileFromRootFolderToSubfolder(@TempDir Path root) throws Exception {
                when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                // File and folder before moving
                Path folderA = root.resolve("A");
                Files.createDirectory(folderA);
                Path fileA = root.resolve("A.pdf");
                Files.createFile(fileA);

                // Setup correct BibEntry
                BibEntry entryA = new BibEntry(StandardEntryType.Article);
                entryA.setCitationKey("WeDoNotCare");
                entryA.addFile(new LinkedFile("", "A.pdf", "PDF"));
                List<BibEntry> entries = List.of(entryA);

                // Simulate the move
                Files.move(fileA, folderA.resolve("A.pdf"));

                // Run auto-link
                AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                        externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                // Check auto-link result
                List<LinkedFile> expect = List.of(new LinkedFile("", Path.of("A/A.pdf"), "PDF"));
                List<LinkedFile> actual = entryA.getFiles();
                assertEquals(expect, actual);
            }

            /// ```
            /// CK: WeDoNotCare
            /// From
            /// └── A
            ///     └── A.pdf
            /// to
            /// ├── A
            /// └── A.pdf
            /// ```
            @Test
            void autoLinkMoveFileFromSubfolderToRootFolder(@TempDir Path root) throws Exception {
                when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                // File and folder before moving
                Path folderA = root.resolve("A");
                Files.createDirectory(folderA);
                Path fileA = folderA.resolve("A.pdf");
                Files.createFile(fileA);

                // Setup correct BibEntry
                BibEntry entryA = new BibEntry(StandardEntryType.Article);
                entryA.setCitationKey("WeDoNotCare");
                entryA.addFile(new LinkedFile("", "A/A.pdf", "PDF"));
                List<BibEntry> entries = List.of(entryA);

                // Simulate the move
                Files.move(fileA, root.resolve("A.pdf"));

                // Run auto-link
                AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                        externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                // Check auto-link result
                List<LinkedFile> expect = List.of(new LinkedFile("", Path.of("A.pdf"), "PDF"));
                List<LinkedFile> actual = entryA.getFiles();
                assertEquals(expect, actual);
            }

            /// ```
            /// From
            /// CK: WeDoNotCare
            /// ├── A
            /// │   └── A.pdf
            /// └── B
            /// to
            /// ├── A
            /// └── B
            ///     └── A.pdf
            /// ```
            @Test
            void autoLinkMoveFileFromSubfolderToSubfolder(@TempDir Path root) throws Exception {
                when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                // File and folder before moving
                Path folderA = root.resolve("A");
                Path folderB = root.resolve("B");
                Files.createDirectory(folderA);
                Files.createDirectory(folderB);
                Path fileA = folderA.resolve("A.pdf");
                Files.createFile(fileA);

                // Setup correct BibEntry
                BibEntry entryA = new BibEntry(StandardEntryType.Article);
                entryA.setCitationKey("WeDoNotCare");
                entryA.addFile(new LinkedFile("", "A/A.pdf", "PDF"));
                List<BibEntry> entries = List.of(entryA);

                // Simulate the move
                Files.move(fileA, folderB.resolve("A.pdf"));

                // Run auto-link
                AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                        externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                // Check auto-link result
                List<LinkedFile> expect = List.of(new LinkedFile("", Path.of("B/A.pdf"), "PDF"));
                List<LinkedFile> actual = entryA.getFiles();
                assertEquals(expect, actual);
            }

            /// ```
            /// CK: WeDoNotCare
            /// From
            /// ├── A.pdf
            /// └── A
            /// to
            /// ├── A.pdf
            /// └── A
            ///     └── A.pdf
            /// ```
            @Test
            void noAutoLinkCopyFileFromRootFolderToSubfolder(@TempDir Path root) throws Exception {
                when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                // File and folder before moving
                Path folderA = root.resolve("A");
                Files.createDirectory(folderA);
                Path fileA = root.resolve("A.pdf");
                Files.createFile(fileA);

                // Setup correct BibEntry
                BibEntry entryA = new BibEntry(StandardEntryType.Article);
                entryA.setCitationKey("WeDoNotCare");
                entryA.addFile(new LinkedFile("", "A.pdf", "PDF"));
                List<BibEntry> entries = List.of(entryA);

                // Simulate the copy
                Files.copy(fileA, folderA.resolve("A.pdf"));

                // Run auto-link
                AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                        externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                // Check auto-link result
                List<LinkedFile> expect = List.of(new LinkedFile("", Path.of("A.pdf"), "PDF"));
                List<LinkedFile> actual = entryA.getFiles();
                assertEquals(expect, actual);
            }

            /// ```
            /// CK: WeDoNotCare
            /// From
            /// └── A
            ///     └── A.pdf
            /// to
            /// ├── A.pdf
            /// └── A
            ///     └── A.pdf
            /// ```
            @Test
            void noAutoLinkCopyFileFromSubfolderToRootFolder(@TempDir Path root) throws Exception {
                when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                // File and folder before moving
                Path folderA = root.resolve("A");
                Files.createDirectory(folderA);
                Path fileA = folderA.resolve("A.pdf");
                Files.createFile(fileA);

                // Setup correct BibEntry
                BibEntry entryA = new BibEntry(StandardEntryType.Article);
                entryA.setCitationKey("WeDoNotCare");
                entryA.addFile(new LinkedFile("", "A/A.pdf", "PDF"));
                List<BibEntry> entries = List.of(entryA);

                // Simulate the copy
                Files.copy(fileA, root.resolve("A.pdf"));

                // Run auto-link
                AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                        externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                // Check auto-link result
                List<LinkedFile> expect = List.of(new LinkedFile("", Path.of("A/A.pdf"), "PDF"));
                List<LinkedFile> actual = entryA.getFiles();
                assertEquals(expect, actual);
            }

            /// ```
            /// CK: WeDoNotCare
            /// From
            /// ├── A
            /// │   └── A.pdf
            /// └── B
            /// to
            /// ├── A
            /// │   └── A.pdf
            /// └── B
            ///     └── A.pdf
            /// ```
            @Test
            void noAutoLinkCopyFileFromSubfolderToSubfolder(@TempDir Path root) throws Exception {
                when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                // File and folder before moving
                Path folderA = root.resolve("A");
                Path folderB = root.resolve("B");
                Files.createDirectory(folderA);
                Files.createDirectory(folderB);
                Path fileA = folderA.resolve("A.pdf");
                Files.createFile(fileA);

                // Setup correct BibEntry
                BibEntry entryA = new BibEntry(StandardEntryType.Article);
                entryA.setCitationKey("WeDoNotCare");
                entryA.addFile(new LinkedFile("", "A/A.pdf", "PDF"));
                List<BibEntry> entries = List.of(entryA);

                // Simulate the move
                Files.copy(fileA, folderB.resolve("A.pdf"));

                // Run auto-link
                AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                        externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                // Check auto-link result
                List<LinkedFile> expect = List.of(new LinkedFile("", Path.of("A/A.pdf"), "PDF"));
                List<LinkedFile> actual = entryA.getFiles();
                assertEquals(expect, actual);
            }
        }

        @Nested
        @DisplayName("byCitationKeyAndBrokenLinkedFileName")
        class byCitationKeyAndBrokenLinkedFileName {

            /// ```
            /// CK: AAA
            /// From
            /// ├── AAA.pdf
            /// └── A
            /// to
            /// └── A
            ///     └── AAA.pdf
            /// ```
            @Test
            void autoLinkMoveFileFromRootFolderToSubFolderByBothBrokenLinkedFileNameAndCitationKey(@TempDir Path root) throws Exception {
                when(autoLinkPrefs.getCitationKeyDependency()).thenReturn(AutoLinkPreferences.CitationKeyDependency.EXACT);
                when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));
                // File and folder before moving
                Path folderA = root.resolve("A");
                Files.createDirectory(folderA);
                Path fileA = root.resolve("AAA.pdf");
                Files.createFile(fileA);

                // Setup correct BibEntry
                BibEntry entryA = new BibEntry(StandardEntryType.Article);
                entryA.setCitationKey("AAA");
                entryA.addFile(new LinkedFile("", "AAA.pdf", "PDF"));
                List<BibEntry> entries = List.of(entryA);

                // Simulate the move
                Files.move(fileA, folderA.resolve("AAA.pdf"));

                // Run auto-link
                AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                        externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                // Check auto-link result
                List<LinkedFile> expect = List.of(new LinkedFile("", Path.of("A/AAA.pdf"), "PDF"));
                List<LinkedFile> actual = entryA.getFiles();
                assertEquals(expect, actual);
            }

            /// ```
            /// CK: AAA
            /// From
            /// └── A
            ///     └── AAA.pdf
            /// to
            /// ├── AAA.pdf
            /// └── A
            ///
            @Test
            void autoLinkMoveFileFromSubFolderToRootFolderByBothBrokenLinkedFileNameAndCitationKey(@TempDir Path root) throws Exception {
                when(autoLinkPrefs.getCitationKeyDependency()).thenReturn(AutoLinkPreferences.CitationKeyDependency.EXACT);
                when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));
                // File and folder before moving
                Path folderA = root.resolve("A");
                Files.createDirectory(folderA);
                Path fileA = folderA.resolve("AAA.pdf");
                Files.createFile(fileA);

                // Setup correct BibEntry
                BibEntry entryA = new BibEntry(StandardEntryType.Article);
                entryA.setCitationKey("AAA");
                entryA.addFile(new LinkedFile("", "A/AAA.pdf", "PDF"));
                List<BibEntry> entries = List.of(entryA);

                // Simulate the move
                Files.move(fileA, root.resolve("AAA.pdf"));

                // Run auto-link
                AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                        externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                // Check auto-link result
                List<LinkedFile> expect = List.of(new LinkedFile("", Path.of("AAA.pdf"), "PDF"));
                List<LinkedFile> actual = entryA.getFiles();
                assertEquals(expect, actual);
            }

            /// ```
            /// CK: AAA
            /// From
            /// ├── A
            /// │   └── AAA.pdf
            /// └── B
            /// to
            /// ├── A
            /// │   └── A.pdf
            /// └── B
            ///     └── AAA.pdf
            /// ```
            @Test
            void noAutoLinkCopyFileFromSubfolderToSubfolderByBothBrokenLinkedFileNameAndCitationKey(@TempDir Path root) throws Exception {
                when(databaseContext.getFileDirectories(any())).thenReturn(Collections.singletonList(root));

                // File and folder before moving
                Path folderA = root.resolve("A");
                Path folderB = root.resolve("B");
                Files.createDirectory(folderA);
                Files.createDirectory(folderB);
                Path fileA = folderA.resolve("AAA.pdf");
                Files.createFile(fileA);

                // Setup correct BibEntry
                BibEntry entryA = new BibEntry(StandardEntryType.Article);
                entryA.setCitationKey("AAA");
                entryA.addFile(new LinkedFile("", "A/AAA.pdf", "PDF"));
                List<BibEntry> entries = List.of(entryA);

                // Simulate the move
                Files.copy(fileA, folderB.resolve("AAA.pdf"));

                // Run auto-link
                AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext,
                        externalApplicationsPreferences, filePreferences, autoLinkPrefs);
                util.linkAssociatedFiles(entries, onLinkedFilesUpdated);

                // Check auto-link result
                List<LinkedFile> expect = List.of(new LinkedFile("", Path.of("A/AAA.pdf"), "PDF"));
                List<LinkedFile> actual = entryA.getFiles();
                assertEquals(expect, actual);
            }
        }
    }
  
    @Test
    void IssueA_discoversLocalFile(@TempDir Path tempDir) throws Exception {
        // The system will look for a file named TestName.pdf
        String citationKey = "TestName";

        // Create a BibEntry object - represents a single publication (like a row in a database)
        // We do NOT use .withFiles() in main file, which means this entry currently has 0 files attached to it
        BibEntry entry = new BibEntry(StandardEntryType.Article)
                .withCitationKey(citationKey);

        // We define the exact path where we want to place our fake PDF file
        Path expectedPdf = tempDir.resolve(citationKey + ".pdf");

        // Now TestName.pdf will exist in the folder.
        Files.createFile(expectedPdf);

        when(databaseContext.getFileDirectories(filePreferences)).thenReturn(List.of(tempDir));
        when(autoLinkPrefs.getRegularExpression()).thenReturn(".*");

        // Create a fake ExternalFileType object. The utility uses this to know what file extensions are allowed
        ExternalFileType pdfFileType = mock(ExternalFileType.class);
        
        // Force our fake file type to act exactly like a PDF

        when(pdfFileType.getExtension()).thenReturn("pdf");
        when(pdfFileType.getName()).thenReturn("PDF");
        when(externalApplicationsPreferences.getExternalFileTypes()).thenReturn(javafx.collections.FXCollections.observableSet(pdfFileType));

        // Now that all our fake settings (mocks) are ready, we create the actual AutoSetFileLinksUtil
        // We pass in all the mocked preferences so the utility thinks it is running inside the real JabRef application
        AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(
                databaseContext,
                externalApplicationsPreferences,
                filePreferences,
                autoLinkPrefs
        );

        Collection<LinkedFile> foundFiles = util.findAssociatedNotLinkedFiles(entry);

        assertEquals(1, foundFiles.size(), "Should discover exactly one matching file on the hard disk.");

        LinkedFile discoveredFile = foundFiles.iterator().next();
        // The discovered file link should match the created filename
        assertEquals(citationKey + ".pdf", discoveredFile.getLink());
    }

    @Test
    void issueH_ignoresAlreadyLinkedFile(@TempDir Path tempDir) throws Exception {
        String citationKey = "TestName";

        Path expectedPdg = tempDir.resolve(citationKey + ".pdf");
        Files.createFile(expectedPdg);

        // LinkedFile object that explicitly points to the PDF that was just created
        // This represents a file that the user has already attached in the past
        LinkedFile existingLink = new LinkedFile("", expectedPdg.toAbsolutePath().toString(), "PDF");

        // Here we use .withFiles() to attach the file right from the start (unlike in test case A)
        BibEntry entry = new BibEntry(StandardEntryType.Article)
                .withCitationKey(citationKey)
                .withFiles(List.of(existingLink));


        // Search our fake hard drive folder
        when(databaseContext.getFileDirectories(filePreferences)).thenReturn(List.of(tempDir));
        // Allow any file name format to be matched
        when(autoLinkPrefs.getRegularExpression()).thenReturn(".*");

        // Configure the fake external file types so it is looking for PDFs
        ExternalFileType pdfFileType = mock(ExternalFileType.class);
        when(pdfFileType.getExtension()).thenReturn("pdf");
        when(pdfFileType.getName()).thenReturn("PDF");
        when(externalApplicationsPreferences.getExternalFileTypes()).thenReturn(javafx.collections.FXCollections.observableSet(pdfFileType));

        AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(
                databaseContext,
                externalApplicationsPreferences,
                filePreferences,
                autoLinkPrefs
        );

        Collection<LinkedFile> foundFiles = util.findAssociatedNotLinkedFiles(entry);

        // Because the PDF on the hard drive is already stored inside the BibEntry's file list, 
        // the utility should filter it out. We expect exactly 0 new files to be discovered
        assertEquals(0, foundFiles.size());
    }
  
  @Test
    void IssueA_discoversLocalFile(@TempDir Path tempDir) throws Exception {
        // The system will look for a file named TestName.pdf
        String citationKey = "TestName";

        // Create a BibEntry object - represents a single publication (like a row in a database)
        // We do NOT use .withFiles() in main file, which means this entry currently has 0 files attached to it
        BibEntry entry = new BibEntry(StandardEntryType.Article)
                .withCitationKey(citationKey);

        // We define the exact path where we want to place our fake PDF file
        Path expectedPdf = tempDir.resolve(citationKey + ".pdf");

        // Now TestName.pdf will exist in the folder.
        Files.createFile(expectedPdf);

        when(databaseContext.getFileDirectories(filePreferences)).thenReturn(List.of(tempDir));
        when(autoLinkPrefs.getRegularExpression()).thenReturn(".*");

        // Create a fake ExternalFileType object. The utility uses this to know what file extensions are allowed
        ExternalFileType pdfFileType = mock(ExternalFileType.class);
        
        // Force our fake file type to act exactly like a PDF

        when(pdfFileType.getExtension()).thenReturn("pdf");
        when(pdfFileType.getName()).thenReturn("PDF");
        when(externalApplicationsPreferences.getExternalFileTypes()).thenReturn(javafx.collections.FXCollections.observableSet(pdfFileType));

        // Now that all our fake settings (mocks) are ready, we create the actual AutoSetFileLinksUtil
        // We pass in all the mocked preferences so the utility thinks it is running inside the real JabRef application
        AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(
                databaseContext,
                externalApplicationsPreferences,
                filePreferences,
                autoLinkPrefs
        );

        Collection<LinkedFile> foundFiles = util.findAssociatedNotLinkedFiles(entry);

        assertEquals(1, foundFiles.size(), "Should discover exactly one matching file on the hard disk.");

        LinkedFile discoveredFile = foundFiles.iterator().next();
        // The discovered file link should match the created filename
        assertEquals(citationKey + ".pdf", discoveredFile.getLink());
    }

    @Test
    void issueH_ignoresAlreadyLinkedFile(@TempDir Path tempDir) throws Exception {
        String citationKey = "TestName";

        Path expectedPdg = tempDir.resolve(citationKey + ".pdf");
        Files.createFile(expectedPdg);

        // LinkedFile object that explicitly points to the PDF that was just created
        // This represents a file that the user has already attached in the past
        LinkedFile existingLink = new LinkedFile("", expectedPdg.toAbsolutePath().toString(), "PDF");

        // Here we use .withFiles() to attach the file right from the start (unlike in test case A)
        BibEntry entry = new BibEntry(StandardEntryType.Article)
                .withCitationKey(citationKey)
                .withFiles(List.of(existingLink));


        // Search our fake hard drive folder
        when(databaseContext.getFileDirectories(filePreferences)).thenReturn(List.of(tempDir));
        // Allow any file name format to be matched
        when(autoLinkPrefs.getRegularExpression()).thenReturn(".*");

        // Configure the fake external file types so it is looking for PDFs
        ExternalFileType pdfFileType = mock(ExternalFileType.class);
        when(pdfFileType.getExtension()).thenReturn("pdf");
        when(pdfFileType.getName()).thenReturn("PDF");
        when(externalApplicationsPreferences.getExternalFileTypes()).thenReturn(javafx.collections.FXCollections.observableSet(pdfFileType));

        AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(
                databaseContext,
                externalApplicationsPreferences,
                filePreferences,
                autoLinkPrefs
        );

        Collection<LinkedFile> foundFiles = util.findAssociatedNotLinkedFiles(entry);

        // Because the PDF on the hard drive is already stored inside the BibEntry's file list, 
        // the utility should filter it out. We expect exactly 0 new files to be discovered
        assertEquals(0, foundFiles.size());
    }
  
    // Scenario D (#380)
    // BibEntry with no file field, no file on disk, no DOI, DOI cannot be determined
    // Expected: no file is linked
    @Test
    void findAssociatedNotLinkedFilesReturnsEmptyWhenNoFileAndNoDoi(@TempDir Path tempDir) throws Exception {
        // Directory with no files
        when(databaseContext.getFileDirectories(any())).thenReturn(List.of(tempDir));

        // No file field, no DOI, and title is nonsense so DOI cannot be derived from it
        BibEntry entryD = new BibEntry(StandardEntryType.Article)
                .withCitationKey("UnmatchableKey")
                .withField(StandardField.TITLE, "xyzzy12345nonexistent");

        // Pass in all the mocked preferences so the utility runs as if inside the real JabRef application
        AutoSetFileLinksUtil util = new AutoSetFileLinksUtil(databaseContext, externalApplicationsPreferences, filePreferences, autoLinkPrefs);

        // Nothing to find so result should be empty
        assertEquals(List.of(), util.findAssociatedNotLinkedFiles(entryD));
    }
}
