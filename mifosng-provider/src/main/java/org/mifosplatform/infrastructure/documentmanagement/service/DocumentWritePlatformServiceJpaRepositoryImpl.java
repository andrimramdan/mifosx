package org.mifosplatform.infrastructure.documentmanagement.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.mifosplatform.infrastructure.core.data.CommandProcessingResult;
import org.mifosplatform.infrastructure.core.exception.PlatformDataIntegrityException;
import org.mifosplatform.infrastructure.core.service.FileUtils;
import org.mifosplatform.infrastructure.documentmanagement.command.DocumentCommand;
import org.mifosplatform.infrastructure.documentmanagement.command.DocumentCommandValidator;
import org.mifosplatform.infrastructure.documentmanagement.domain.Document;
import org.mifosplatform.infrastructure.documentmanagement.domain.DocumentRepository;
import org.mifosplatform.infrastructure.documentmanagement.exception.DocumentManagementException;
import org.mifosplatform.infrastructure.documentmanagement.exception.DocumentNotFoundException;
import org.mifosplatform.infrastructure.documentmanagement.exception.InvalidEntityTypeForDocumentManagementException;
import org.mifosplatform.infrastructure.security.service.PlatformSecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentWritePlatformServiceJpaRepositoryImpl implements DocumentWritePlatformService {

    private final static Logger logger = LoggerFactory.getLogger(DocumentWritePlatformServiceJpaRepositoryImpl.class);

    private final PlatformSecurityContext context;
    private final DocumentRepository documentRepository;

    @Autowired
    public DocumentWritePlatformServiceJpaRepositoryImpl(final PlatformSecurityContext context, final DocumentRepository documentRepository) {
        this.context = context;
        this.documentRepository = documentRepository;
    }

    @Transactional
    @Override
    public Long createDocument(final DocumentCommand documentCommand, final InputStream inputStream) {
        try {
            context.authenticatedUser();

            DocumentCommandValidator validator = new DocumentCommandValidator(documentCommand);

            validateParentEntityType(documentCommand);

            validator.validateForCreate();

            String fileUploadLocation = FileUtils.generateFileParentDirectory(documentCommand.getParentEntityType(),
                    documentCommand.getParentEntityId());

            /** Recursively create the directory if it does not exist **/
            if (!new File(fileUploadLocation).isDirectory()) {
                new File(fileUploadLocation).mkdirs();
            }

            String fileLocation = FileUtils.saveToFileSystem(inputStream, fileUploadLocation, documentCommand.getFileName());

            Document document = Document.createNew(documentCommand.getParentEntityType(), documentCommand.getParentEntityId(),
                    documentCommand.getName(), documentCommand.getFileName(), documentCommand.getSize(), documentCommand.getType(),
                    documentCommand.getDescription(), fileLocation);

            this.documentRepository.save(document);

            return document.getId();
        } catch (DataIntegrityViolationException dve) {
            logger.error(dve.getMessage(), dve);
            throw new PlatformDataIntegrityException("error.msg.document.unknown.data.integrity.issue",
                    "Unknown data integrity issue with resource.");
        } catch (IOException ioe) {
            logger.error(ioe.getMessage(), ioe);
            throw new DocumentManagementException(documentCommand.getName());
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult updateDocument(final DocumentCommand documentCommand, final InputStream inputStream) {
        try {
            context.authenticatedUser();

            String oldLocation = null;
            DocumentCommandValidator validator = new DocumentCommandValidator(documentCommand);
            validator.validateForUpdate();
            // TODO check if entity id is valid and within data scope for the
            // user
            Document documentForUpdate = this.documentRepository.findOne(documentCommand.getId());
            if (documentForUpdate == null) { throw new DocumentNotFoundException(documentCommand.getParentEntityType(),
                    documentCommand.getParentEntityId(), documentCommand.getId()); }
            oldLocation = documentForUpdate.getLocation();
            // if a new file is also passed in
            if (inputStream != null && documentCommand.isFileNameChanged()) {
                String fileUploadLocation = FileUtils.generateFileParentDirectory(documentCommand.getParentEntityType(),
                        documentCommand.getParentEntityId());

                /** Recursively create the directory if it does not exist **/
                if (!new File(fileUploadLocation).isDirectory()) {
                    new File(fileUploadLocation).mkdirs();
                }

                // TODO replace file system appender with an Amazon S3 appender
                String fileLocation = FileUtils.saveToFileSystem(inputStream, fileUploadLocation, documentCommand.getFileName());
                documentCommand.setLocation(fileLocation);
            }

            documentForUpdate.update(documentCommand);

            if (inputStream != null && documentCommand.isFileNameChanged()) {
                // delete previous file
                deleteFile(documentCommand.getName(), oldLocation);
            }

            this.documentRepository.saveAndFlush(documentForUpdate);

            return new CommandProcessingResult(documentForUpdate.getId());
        } catch (DataIntegrityViolationException dve) {
            logger.error(dve.getMessage(), dve);
            throw new PlatformDataIntegrityException("error.msg.document.unknown.data.integrity.issue",
                    "Unknown data integrity issue with resource.");
        } catch (IOException ioe) {
            logger.error(ioe.getMessage(), ioe);
            throw new DocumentManagementException(documentCommand.getName());
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult deleteDocument(final DocumentCommand documentCommand) {
        context.authenticatedUser();

        validateParentEntityType(documentCommand);
        // TODO: Check document is present under this entity Id
        Document document = this.documentRepository.findOne(documentCommand.getId());
        if (document == null) { throw new DocumentNotFoundException(documentCommand.getParentEntityType(),
                documentCommand.getParentEntityId(), documentCommand.getId()); }

        this.documentRepository.delete(document);
        deleteFile(document.getName(), document.getLocation());
        return new CommandProcessingResult(document.getId());
    }

    private void deleteFile(String documentName, String location) {
        File fileToBeDeleted = new File(location);
        boolean fileDeleted = fileToBeDeleted.delete();
        if (!fileDeleted) { throw new DocumentManagementException(documentName); }
    }

    private void validateParentEntityType(DocumentCommand documentCommand) {
        if (!checkValidEntityType(documentCommand.getParentEntityType())) { throw new InvalidEntityTypeForDocumentManagementException(
                documentCommand.getParentEntityType()); }
    }

    private static boolean checkValidEntityType(final String entityType) {

        for (DOCUMENT_MANAGEMENT_ENTITY entities : DOCUMENT_MANAGEMENT_ENTITY.values()) {
            if (entities.name().equalsIgnoreCase(entityType)) { return true; }
        }
        return false;
    }

    /*** Entities for document Management **/
    public static enum DOCUMENT_MANAGEMENT_ENTITY {
        CLIENTS, CLIENT_IDENTIFIERS, STAFF, LOANS, SAVINGS;

        @Override
        public String toString() {
            return name().toString().toLowerCase();
        }
    }
}