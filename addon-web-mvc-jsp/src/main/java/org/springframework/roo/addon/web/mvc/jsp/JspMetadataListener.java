package org.springframework.roo.addon.web.mvc.jsp;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.logging.Logger;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.springframework.roo.addon.propfiles.PropFileOperations;
import org.springframework.roo.addon.web.mvc.controller.addon.details.FinderMetadataDetails;
import org.springframework.roo.addon.web.mvc.controller.addon.details.JavaTypeMetadataDetails;
import org.springframework.roo.addon.web.mvc.controller.addon.details.JavaTypePersistenceMetadataDetails;
import org.springframework.roo.addon.web.mvc.controller.addon.details.WebMetadataService;
import org.springframework.roo.addon.web.mvc.controller.addon.finder.WebFinderMetadata;
import org.springframework.roo.addon.web.mvc.controller.addon.scaffold.WebScaffoldMetadata;
import org.springframework.roo.addon.web.mvc.jsp.menu.MenuOperations;
import org.springframework.roo.addon.web.mvc.jsp.roundtrip.XmlRoundTripFileManager;
import org.springframework.roo.addon.web.mvc.jsp.tiles.TilesOperations;
import org.springframework.roo.classpath.PhysicalTypeIdentifier;
import org.springframework.roo.classpath.PhysicalTypeMetadata;
import org.springframework.roo.classpath.TypeLocationService;
import org.springframework.roo.classpath.details.BeanInfoUtils;
import org.springframework.roo.classpath.details.ClassOrInterfaceTypeDetails;
import org.springframework.roo.classpath.details.FieldMetadata;
import org.springframework.roo.classpath.details.ItdTypeDetails;
import org.springframework.roo.classpath.details.MemberFindingUtils;
import org.springframework.roo.classpath.details.MemberHoldingTypeDetails;
import org.springframework.roo.classpath.details.MethodMetadata;
import org.springframework.roo.classpath.itd.ItdTypeDetailsProvidingMetadataItem;
import org.springframework.roo.classpath.scanner.MemberDetails;
import org.springframework.roo.metadata.MetadataDependency;
import org.springframework.roo.metadata.MetadataDependencyRegistry;
import org.springframework.roo.metadata.MetadataIdentificationUtils;
import org.springframework.roo.metadata.MetadataItem;
import org.springframework.roo.metadata.MetadataNotificationListener;
import org.springframework.roo.metadata.MetadataProvider;
import org.springframework.roo.metadata.MetadataService;
import org.springframework.roo.metadata.internal.MetadataDependencyRegistryTracker;
import org.springframework.roo.model.JavaSymbolName;
import org.springframework.roo.model.JavaType;
import org.springframework.roo.model.JpaJavaType;
import org.springframework.roo.model.RooJavaType;
import org.springframework.roo.process.manager.FileManager;
import org.springframework.roo.project.LogicalPath;
import org.springframework.roo.project.Path;
import org.springframework.roo.project.PathResolver;
import org.springframework.roo.project.ProjectOperations;
import org.springframework.roo.support.logging.HandlerUtils;
import org.springframework.roo.support.util.FileUtils;
import org.springframework.roo.support.util.XmlUtils;

/**
 * Listens for {@link WebScaffoldMetadata} and produces JSPs when requested by
 * that metadata.
 * 
 * @author Stefan Schmidt
 * @author Ben Alex
 * @author Enrique Ruiz at DISID Corporation S.L.
 * @since 1.0
 */
@Component
@Service
public class JspMetadataListener implements MetadataProvider,
        MetadataNotificationListener {

    private static final Logger LOGGER = HandlerUtils
            .getLogger(JspOperationsImpl.class);

    // ------------ OSGi component attributes ----------------
    private BundleContext context;

    private static final String WEB_INF_VIEWS = "/WEB-INF/views/";

    private FileManager fileManager;
    private JspOperations jspOperations;
    private MenuOperations menuOperations;
    private MetadataDependencyRegistry metadataDependencyRegistry;
    private MetadataService metadataService;
    private ProjectOperations projectOperations;
    private PropFileOperations propFileOperations;
    private TilesOperations tilesOperations;
    private TypeLocationService typeLocationService;
    private WebMetadataService webMetadataService;
    private XmlRoundTripFileManager xmlRoundTripFileManager;

    private final Map<JavaType, String> formBackingObjectTypesToLocalMids = new HashMap<JavaType, String>();

    protected MetadataDependencyRegistryTracker registryTracker = null;

    /**
     * This service is being activated so setup it:
     * <ul>
     * <li>Create and open the {@link MetadataDependencyRegistryTracker}.</li>
     * </ul>
     */
    protected void activate(final ComponentContext cContext) {
        this.context = cContext.getBundleContext();
        this.registryTracker = new MetadataDependencyRegistryTracker(context,
                this, new MetadataDependency(
                        WebScaffoldMetadata.getMetadataIdentiferType(),
                        getProvidesType()), 
                new MetadataDependency(
                        WebFinderMetadata.getMetadataIdentiferType(),
                        getProvidesType()));
        this.registryTracker.open();
    }

    /**
     * This service is being deactivated so unregister upstream-downstream 
     * dependencies, triggers, matchers and listeners.
     * 
     * @param context
     */
    protected void deactivate(final ComponentContext context) {
        MetadataDependencyRegistry registry = this.registryTracker.getService();
        registry.removeNotificationListener(this);
        registry.deregisterDependency(WebScaffoldMetadata.getMetadataIdentiferType(),
                getProvidesType());
        registry.deregisterDependency(WebFinderMetadata.getMetadataIdentiferType(),
                getProvidesType());
        this.registryTracker.close();
    }

    public MetadataItem get(final String jspMetadataId) {
        // Work out the MIDs of the other metadata we depend on
        // NB: The JavaType and Path are to the corresponding web scaffold
        // controller class

        final String webScaffoldMetadataKey = WebScaffoldMetadata
                .createIdentifier(JspMetadata.getJavaType(jspMetadataId),
                        JspMetadata.getPath(jspMetadataId));
        final WebScaffoldMetadata webScaffoldMetadata = (WebScaffoldMetadata) getMetadataService()
                .get(webScaffoldMetadataKey);
        if (webScaffoldMetadata == null || !webScaffoldMetadata.isValid()) {
            // Can't get the corresponding scaffold, so we certainly don't need
            // to manage any JSPs at this time
            return null;
        }

        final JavaType formBackingType = webScaffoldMetadata
                .getAnnotationValues().getFormBackingObject();
        final MemberDetails memberDetails = getWebMetadataService()
                .getMemberDetails(formBackingType);
        final JavaTypeMetadataDetails formBackingTypeMetadataDetails = getWebMetadataService()
                .getJavaTypeMetadataDetails(formBackingType, memberDetails,
                        jspMetadataId);
        Validate.notNull(formBackingTypeMetadataDetails,
                "Unable to obtain metadata for type %s",
                formBackingType.getFullyQualifiedTypeName());

        formBackingObjectTypesToLocalMids.put(formBackingType, jspMetadataId);

        final SortedMap<JavaType, JavaTypeMetadataDetails> relatedTypeMd = getWebMetadataService()
                .getRelatedApplicationTypeMetadata(formBackingType,
                        memberDetails, jspMetadataId);
        final JavaTypeMetadataDetails formbackingTypeMetadata = relatedTypeMd
                .get(formBackingType);
        Validate.notNull(formbackingTypeMetadata,
                "Form backing type metadata required");
        final JavaTypePersistenceMetadataDetails formBackingTypePersistenceMetadata = formbackingTypeMetadata
                .getPersistenceDetails();
        if (formBackingTypePersistenceMetadata == null) {
            return null;
        }
        final ClassOrInterfaceTypeDetails formBackingTypeDetails = getTypeLocationService()
                .getTypeDetails(formBackingType);
        final LogicalPath formBackingTypePath = PhysicalTypeIdentifier
                .getPath(formBackingTypeDetails.getDeclaredByMetadataId());
        getMetadataDependencyRegistry().registerDependency(
                PhysicalTypeIdentifier.createIdentifier(formBackingType,
                        formBackingTypePath),
                JspMetadata.createIdentifier(formBackingType,
                        formBackingTypePath));
        final LogicalPath path = JspMetadata.getPath(jspMetadataId);

        // Install web artifacts only if Spring MVC config is missing
        // TODO: Remove this call when 'controller' commands are gone
        final PathResolver pathResolver = getProjectOperations()
                .getPathResolver();
        final LogicalPath webappPath = LogicalPath.getInstance(
                Path.SRC_MAIN_WEBAPP, path.getModule());

        if (!getFileManager().exists(
                pathResolver.getIdentifier(webappPath, WEB_INF_VIEWS))) {
            getJspOperations().installCommonViewArtefacts(path.getModule());
        }

        installImage(webappPath, "images/show.png");
        if (webScaffoldMetadata.getAnnotationValues().isUpdate()) {
            installImage(webappPath, "images/update.png");
        }
        if (webScaffoldMetadata.getAnnotationValues().isDelete()) {
            installImage(webappPath, "images/delete.png");
        }

        final List<FieldMetadata> eligibleFields = getWebMetadataService()
                .getScaffoldEligibleFieldMetadata(formBackingType,
                        memberDetails, jspMetadataId);
        if (eligibleFields.isEmpty()
                && formBackingTypePersistenceMetadata.getRooIdentifierFields()
                        .isEmpty()) {
            return null;
        }
        final JspViewManager viewManager = new JspViewManager(eligibleFields,
                webScaffoldMetadata.getAnnotationValues(), relatedTypeMd);

        String controllerPath = webScaffoldMetadata.getAnnotationValues()
                .getPath();
        if (controllerPath.startsWith("/")) {
            controllerPath = controllerPath.substring(1);
        }

        // Make the holding directory for this controller
        final String destinationDirectory = pathResolver.getIdentifier(
                webappPath, WEB_INF_VIEWS + controllerPath);
        if (!getFileManager().exists(destinationDirectory)) {
            getFileManager().createDirectory(destinationDirectory);
        }
        else {
            final File file = new File(destinationDirectory);
            Validate.isTrue(file.isDirectory(),
                    "%s is a file, when a directory was expected",
                    destinationDirectory);
        }

        // By now we have a directory to put the JSPs inside
        final String listPath1 = destinationDirectory + "/list.jspx";
        getXmlRoundTripFileManager().writeToDiskIfNecessary(listPath1,
                viewManager.getListDocument());
        getTilesOperations().addViewDefinition(controllerPath, webappPath,
                controllerPath + "/" + "list",
                TilesOperations.DEFAULT_TEMPLATE,
                WEB_INF_VIEWS + controllerPath + "/list.jspx");

        final String showPath = destinationDirectory + "/show.jspx";
        getXmlRoundTripFileManager().writeToDiskIfNecessary(showPath,
                viewManager.getShowDocument());
        getTilesOperations().addViewDefinition(controllerPath, webappPath,
                controllerPath + "/" + "show",
                TilesOperations.DEFAULT_TEMPLATE,
                WEB_INF_VIEWS + controllerPath + "/show.jspx");

        final Map<String, String> properties = new LinkedHashMap<String, String>();

        final JavaSymbolName categoryName = new JavaSymbolName(
                formBackingType.getSimpleTypeName());
        properties.put("menu_category_"
                + categoryName.getSymbolName().toLowerCase() + "_label",
                categoryName.getReadableSymbolName());

        final JavaSymbolName newMenuItemId = new JavaSymbolName("new");
        properties.put("menu_item_"
                + categoryName.getSymbolName().toLowerCase() + "_"
                + newMenuItemId.getSymbolName().toLowerCase() + "_label",
                new JavaSymbolName(formBackingType.getSimpleTypeName())
                        .getReadableSymbolName());

        if (webScaffoldMetadata.getAnnotationValues().isCreate()) {
            final String listPath = destinationDirectory + "/create.jspx";
            getXmlRoundTripFileManager().writeToDiskIfNecessary(listPath,
                    viewManager.getCreateDocument());
            // Add 'create new' menu item
            getMenuOperations().addMenuItem(categoryName, newMenuItemId,
                    "global_menu_new", "/" + controllerPath + "?form",
                    MenuOperations.DEFAULT_MENU_ITEM_PREFIX, webappPath);
            getTilesOperations().addViewDefinition(controllerPath, webappPath,
                    controllerPath + "/" + "create",
                    TilesOperations.DEFAULT_TEMPLATE,
                    WEB_INF_VIEWS + controllerPath + "/create.jspx");
        }
        else {
            getMenuOperations().cleanUpMenuItem(categoryName,
                    new JavaSymbolName("new"),
                    MenuOperations.DEFAULT_MENU_ITEM_PREFIX, webappPath);
            getTilesOperations()
                    .removeViewDefinition(controllerPath + "/" + "create",
                            controllerPath, webappPath);
        }
        if (webScaffoldMetadata.getAnnotationValues().isUpdate()) {
            final String listPath = destinationDirectory + "/update.jspx";
            getXmlRoundTripFileManager().writeToDiskIfNecessary(listPath,
                    viewManager.getUpdateDocument());
            getTilesOperations().addViewDefinition(controllerPath, webappPath,
                    controllerPath + "/" + "update",
                    TilesOperations.DEFAULT_TEMPLATE,
                    WEB_INF_VIEWS + controllerPath + "/update.jspx");
        }
        else {
            getTilesOperations()
                    .removeViewDefinition(controllerPath + "/" + "update",
                            controllerPath, webappPath);
        }

        // Setup labels for i18n support
        final String resourceId = XmlUtils.convertId("label."
                + formBackingType.getFullyQualifiedTypeName().toLowerCase());
        properties.put(resourceId,
                new JavaSymbolName(formBackingType.getSimpleTypeName())
                        .getReadableSymbolName());

        final String pluralResourceId = XmlUtils.convertId(resourceId
                + ".plural");
        final String plural = formBackingTypeMetadataDetails.getPlural();
        properties.put(pluralResourceId,
                new JavaSymbolName(plural).getReadableSymbolName());

        final JavaTypePersistenceMetadataDetails javaTypePersistenceMetadataDetails = formBackingTypeMetadataDetails
                .getPersistenceDetails();
        Validate.notNull(javaTypePersistenceMetadataDetails,
                "Unable to determine persistence metadata for type %s",
                formBackingType.getFullyQualifiedTypeName());

        if (!javaTypePersistenceMetadataDetails.getRooIdentifierFields()
                .isEmpty()) {
            for (final FieldMetadata idField : javaTypePersistenceMetadataDetails
                    .getRooIdentifierFields()) {
                properties.put(
                        XmlUtils.convertId(resourceId
                                + "."
                                + javaTypePersistenceMetadataDetails
                                        .getIdentifierField().getFieldName()
                                        .getSymbolName()
                                + "."
                                + idField.getFieldName().getSymbolName()
                                        .toLowerCase()), idField.getFieldName()
                                .getReadableSymbolName());
            }
        }

        // If no auto generated value for id, put name in i18n
        if (javaTypePersistenceMetadataDetails.getIdentifierField()
                .getAnnotation(JpaJavaType.GENERATED_VALUE) == null) {
            properties.put(
                    XmlUtils.convertId(resourceId
                            + "."
                            + javaTypePersistenceMetadataDetails
                                    .getIdentifierField().getFieldName()
                                    .getSymbolName().toLowerCase()),
                    javaTypePersistenceMetadataDetails.getIdentifierField()
                            .getFieldName().getReadableSymbolName());
        }

        for (final MethodMetadata method : memberDetails.getMethods()) {
            if (!BeanInfoUtils.isAccessorMethod(method)) {
                continue;
            }

            final FieldMetadata field = BeanInfoUtils
                    .getFieldForJavaBeanMethod(memberDetails, method);
            if (field == null) {
                continue;
            }
            final JavaSymbolName fieldName = field.getFieldName();
            final String fieldResourceId = XmlUtils.convertId(resourceId + "."
                    + fieldName.getSymbolName().toLowerCase());
            if (getTypeLocationService().isInProject(method.getReturnType())
                    && getWebMetadataService().isRooIdentifier(
                            method.getReturnType(),
                            getWebMetadataService().getMemberDetails(
                                    method.getReturnType()))) {
                final JavaTypePersistenceMetadataDetails typePersistenceMetadataDetails = getWebMetadataService()
                        .getJavaTypePersistenceMetadataDetails(
                                method.getReturnType(),
                                getWebMetadataService().getMemberDetails(
                                        method.getReturnType()), jspMetadataId);
                if (typePersistenceMetadataDetails != null) {
                    for (final FieldMetadata f : typePersistenceMetadataDetails
                            .getRooIdentifierFields()) {
                        final String sb = f.getFieldName()
                                .getReadableSymbolName();
                        properties.put(
                                XmlUtils.convertId(resourceId
                                        + "."
                                        + javaTypePersistenceMetadataDetails
                                                .getIdentifierField()
                                                .getFieldName().getSymbolName()
                                        + "."
                                        + f.getFieldName().getSymbolName()
                                                .toLowerCase()),
                                StringUtils.isNotBlank(sb) ? sb : fieldName
                                        .getSymbolName());
                    }
                }
            }
            else if (!method.getMethodName().equals(
                    javaTypePersistenceMetadataDetails
                            .getIdentifierAccessorMethod().getMethodName())
                    || javaTypePersistenceMetadataDetails
                            .getVersionAccessorMethod() != null
                    && !method
                            .getMethodName()
                            .equals(javaTypePersistenceMetadataDetails
                                    .getVersionAccessorMethod().getMethodName())) {
                final String sb = fieldName.getReadableSymbolName();
                properties.put(fieldResourceId, StringUtils.isNotBlank(sb) ? sb
                        : fieldName.getSymbolName());
            }
        }

        if (javaTypePersistenceMetadataDetails.getFindAllMethod() != null) {
            // Add 'list all' menu item
            final JavaSymbolName listMenuItemId = new JavaSymbolName("list");
            getMenuOperations()
                    .addMenuItem(
                            categoryName,
                            listMenuItemId,
                            "global_menu_list",
                            "/"
                                    + controllerPath
                                    + "?page=1&size=${empty param.size ? 10 : param.size}",
                            MenuOperations.DEFAULT_MENU_ITEM_PREFIX, webappPath);
            properties.put("menu_item_"
                    + categoryName.getSymbolName().toLowerCase() + "_"
                    + listMenuItemId.getSymbolName().toLowerCase() + "_label",
                    new JavaSymbolName(plural).getReadableSymbolName());
        }
        else {
            getMenuOperations().cleanUpMenuItem(categoryName,
                    new JavaSymbolName("list"),
                    MenuOperations.DEFAULT_MENU_ITEM_PREFIX, webappPath);
        }

        final String controllerPhysicalTypeId = PhysicalTypeIdentifier
                .createIdentifier(JspMetadata.getJavaType(jspMetadataId),
                        JspMetadata.getPath(jspMetadataId));
        final PhysicalTypeMetadata controllerPhysicalTypeMd = (PhysicalTypeMetadata) getMetadataService()
                .get(controllerPhysicalTypeId);
        if (controllerPhysicalTypeMd == null) {
            return null;
        }
        final MemberHoldingTypeDetails mhtd = controllerPhysicalTypeMd
                .getMemberHoldingTypeDetails();
        if (mhtd == null) {
            return null;
        }
        final List<String> allowedMenuItems = new ArrayList<String>();
        if (MemberFindingUtils.getAnnotationOfType(mhtd.getAnnotations(),
                RooJavaType.ROO_WEB_FINDER) != null) {
            // This controller is annotated with @RooWebFinder
            final Set<FinderMetadataDetails> finderMethodsDetails = getWebMetadataService()
                    .getDynamicFinderMethodsAndFields(formBackingType,
                            memberDetails, jspMetadataId);
            if (finderMethodsDetails == null) {
                return null;
            }
            for (final FinderMetadataDetails finderDetails : finderMethodsDetails) {
                final String finderName = finderDetails
                        .getFinderMethodMetadata().getMethodName()
                        .getSymbolName();
                final String listPath = destinationDirectory + "/" + finderName
                        + ".jspx";
                // Finders only get scaffolded if the finder name is not too
                // long (see ROO-1027)
                if (listPath.length() > 244) {
                    continue;
                }
                getXmlRoundTripFileManager().writeToDiskIfNecessary(listPath,
                        viewManager.getFinderDocument(finderDetails));
                final JavaSymbolName finderLabel = new JavaSymbolName(
                        finderName.replace("find" + plural + "By", ""));

                // Add 'Find by' menu item
                getMenuOperations()
                        .addMenuItem(
                                categoryName,
                                finderLabel,
                                "global_menu_find",
                                "/"
                                        + controllerPath
                                        + "?find="
                                        + finderName.replace("find" + plural,
                                                "")
                                        + "&form"
                                        + "&page=1&size=${empty param.size ? 10 : param.size}",
                                MenuOperations.FINDER_MENU_ITEM_PREFIX,
                                webappPath);
                properties.put("menu_item_"
                        + categoryName.getSymbolName().toLowerCase() + "_"
                        + finderLabel.getSymbolName().toLowerCase() + "_label",
                        finderLabel.getReadableSymbolName());
                allowedMenuItems.add(MenuOperations.FINDER_MENU_ITEM_PREFIX
                        + categoryName.getSymbolName().toLowerCase() + "_"
                        + finderLabel.getSymbolName().toLowerCase());
                for (final JavaSymbolName paramName : finderDetails
                        .getFinderMethodMetadata().getParameterNames()) {
                    properties.put(
                            XmlUtils.convertId(resourceId + "."
                                    + paramName.getSymbolName().toLowerCase()),
                            paramName.getReadableSymbolName());
                }
                getTilesOperations().addViewDefinition(
                        controllerPath,
                        webappPath,
                        controllerPath + "/" + finderName,
                        TilesOperations.DEFAULT_TEMPLATE,
                        WEB_INF_VIEWS + controllerPath + "/" + finderName
                                + ".jspx");
            }
        }

        getMenuOperations().cleanUpFinderMenuItems(categoryName,
                allowedMenuItems, webappPath);

        getPropFileOperations().addProperties(webappPath,
                "WEB-INF/i18n/application.properties", properties, true, false);

        return new JspMetadata(jspMetadataId, webScaffoldMetadata);
    }

    public String getProvidesType() {
        return JspMetadata.getMetadataIdentiferType();
    }

    private void installImage(final LogicalPath path, final String imagePath) {
        final PathResolver pathResolver = getProjectOperations()
                .getPathResolver();
        final String imageFile = pathResolver.getIdentifier(path, imagePath);
        if (!getFileManager().exists(imageFile)) {
            InputStream inputStream = null;
            OutputStream outputStream = null;
            try {
                inputStream = FileUtils.getInputStream(getClass(), imagePath);
                outputStream = getFileManager().createFile(
                        pathResolver.getIdentifier(path, imagePath))
                        .getOutputStream();
                IOUtils.copy(inputStream, outputStream);
            }
            catch (final Exception e) {
                throw new IllegalStateException(
                        "Encountered an error during copying of resources for MVC JSP addon.",
                        e);
            }
            finally {
                IOUtils.closeQuietly(inputStream);
                IOUtils.closeQuietly(outputStream);
            }
        }
    }

    public void notify(final String upstreamDependency,
            String downstreamDependency) {
        if (MetadataIdentificationUtils
                .isIdentifyingClass(downstreamDependency)) {
            // A physical Java type has changed, and determine what the
            // corresponding local metadata identification string would have
            // been
            if (WebScaffoldMetadata.isValid(upstreamDependency)) {
                final JavaType javaType = WebScaffoldMetadata
                        .getJavaType(upstreamDependency);
                final LogicalPath path = WebScaffoldMetadata
                        .getPath(upstreamDependency);
                downstreamDependency = JspMetadata.createIdentifier(javaType,
                        path);
            }
            else if (WebFinderMetadata.isValid(upstreamDependency)) {
                final JavaType javaType = WebFinderMetadata
                        .getJavaType(upstreamDependency);
                final LogicalPath path = WebFinderMetadata
                        .getPath(upstreamDependency);
                downstreamDependency = JspMetadata.createIdentifier(javaType,
                        path);
            }

            // We only need to proceed if the downstream dependency relationship
            // is not already registered
            // (if it's already registered, the event will be delivered directly
            // later on)
            if (getMetadataDependencyRegistry().getDownstream(
                    upstreamDependency).contains(downstreamDependency)) {
                return;
            }
        }
        else if (MetadataIdentificationUtils
                .isIdentifyingInstance(upstreamDependency)) {
            // This is the generic fallback listener, ie from
            // MetadataDependencyRegistry.addListener(this) in the activate()
            // method

            // Get the metadata that just changed
            final MetadataItem metadataItem = getMetadataService().get(
                    upstreamDependency);

            // We don't have to worry about physical type metadata, as we
            // monitor the relevant .java once the DOD governor is first
            // detected
            if (metadataItem == null
                    || !metadataItem.isValid()
                    || !(metadataItem instanceof ItdTypeDetailsProvidingMetadataItem)) {
                // There's something wrong with it or it's not for an ITD, so
                // let's gracefully abort
                return;
            }

            // Let's ensure we have some ITD type details to actually work with
            final ItdTypeDetailsProvidingMetadataItem itdMetadata = (ItdTypeDetailsProvidingMetadataItem) metadataItem;
            final ItdTypeDetails itdTypeDetails = itdMetadata
                    .getMemberHoldingTypeDetails();
            if (itdTypeDetails == null) {
                return;
            }

            final String localMid = formBackingObjectTypesToLocalMids
                    .get(itdTypeDetails.getGovernor().getName());
            if (localMid != null) {
                getMetadataService().evictAndGet(localMid);
            }
            return;
        }

        if (MetadataIdentificationUtils
                .isIdentifyingInstance(downstreamDependency)) {
            getMetadataService().evictAndGet(downstreamDependency);
        }
    }

    public FileManager getFileManager() {
        if (fileManager == null) {
            // Get all Services implement FileManager interface
            try {
                ServiceReference<?>[] references = context
                        .getAllServiceReferences(FileManager.class.getName(),
                                null);

                for (ServiceReference<?> ref : references) {
                    fileManager = (FileManager) context.getService(ref);
                    return fileManager;
                }
                return null;
            }
            catch (InvalidSyntaxException e) {
                LOGGER.warning("Cannot load FileManager on JspMetadataListener.");
                return null;
            }
        }
        else {
            return fileManager;
        }
    }

    public JspOperations getJspOperations() {
        if (jspOperations == null) {
            // Get all Services implement JspOperations interface
            try {
                ServiceReference<?>[] references = context
                        .getAllServiceReferences(JspOperations.class.getName(),
                                null);

                for (ServiceReference<?> ref : references) {
                    jspOperations = (JspOperations) context.getService(ref);
                    return jspOperations;
                }
                return null;
            }
            catch (InvalidSyntaxException e) {
                LOGGER.warning("Cannot load JspOperations on JspMetadataListener.");
                return null;
            }
        }
        else {
            return jspOperations;
        }
    }

    public MenuOperations getMenuOperations() {
        if (menuOperations == null) {
            // Get all Services implement MenuOperations interface
            try {
                ServiceReference<?>[] references = context
                        .getAllServiceReferences(
                                MenuOperations.class.getName(), null);

                for (ServiceReference<?> ref : references) {
                    menuOperations = (MenuOperations) context.getService(ref);
                    return menuOperations;
                }
                return null;
            }
            catch (InvalidSyntaxException e) {
                LOGGER.warning("Cannot load MenuOperations on JspMetadataListener.");
                return null;
            }
        }
        else {
            return menuOperations;
        }
    }

    public MetadataDependencyRegistry getMetadataDependencyRegistry() {
        if (metadataDependencyRegistry == null) {
            // Get all Services implement MetadataDependencyRegistry interface
            try {
                ServiceReference<?>[] references = context
                        .getAllServiceReferences(
                                MetadataDependencyRegistry.class.getName(),
                                null);

                for (ServiceReference<?> ref : references) {
                    metadataDependencyRegistry = (MetadataDependencyRegistry) context
                            .getService(ref);
                    return metadataDependencyRegistry;
                }
                return null;
            }
            catch (InvalidSyntaxException e) {
                LOGGER.warning("Cannot load MetadataDependencyRegistry on JspMetadataListener.");
                return null;
            }
        }
        else {
            return metadataDependencyRegistry;
        }
    }

    public MetadataService getMetadataService() {
        if (metadataService == null) {
            // Get all Services implement MetadataService interface
            try {
                ServiceReference<?>[] references = context
                        .getAllServiceReferences(
                                MetadataService.class.getName(), null);

                for (ServiceReference<?> ref : references) {
                    metadataService = (MetadataService) context.getService(ref);
                    return metadataService;
                }
                return null;
            }
            catch (InvalidSyntaxException e) {
                LOGGER.warning("Cannot load MetadataService on JspMetadataListener.");
                return null;
            }
        }
        else {
            return metadataService;
        }
    }

    public ProjectOperations getProjectOperations() {
        if (projectOperations == null) {
            // Get all Services implement ProjectOperations interface
            try {
                ServiceReference<?>[] references = context
                        .getAllServiceReferences(
                                ProjectOperations.class.getName(), null);

                for (ServiceReference<?> ref : references) {
                    projectOperations = (ProjectOperations) context
                            .getService(ref);
                    return projectOperations;
                }
                return null;
            }
            catch (InvalidSyntaxException e) {
                LOGGER.warning("Cannot load ProjectOperations on JspMetadataListener.");
                return null;
            }
        }
        else {
            return projectOperations;
        }
    }

    public PropFileOperations getPropFileOperations() {
        if (propFileOperations == null) {
            // Get all Services implement PropFileOperations interface
            try {
                ServiceReference<?>[] references = context
                        .getAllServiceReferences(
                                PropFileOperations.class.getName(), null);

                for (ServiceReference<?> ref : references) {
                    propFileOperations = (PropFileOperations) context
                            .getService(ref);
                    return propFileOperations;
                }
                return null;
            }
            catch (InvalidSyntaxException e) {
                LOGGER.warning("Cannot load PropFileOperations on JspMetadataListener.");
                return null;
            }
        }
        else {
            return propFileOperations;
        }
    }

    public TilesOperations getTilesOperations() {
        if (tilesOperations == null) {
            // Get all Services implement TilesOperations interface
            try {
                ServiceReference<?>[] references = context
                        .getAllServiceReferences(
                                TilesOperations.class.getName(), null);

                for (ServiceReference<?> ref : references) {
                    tilesOperations = (TilesOperations) context.getService(ref);
                    return tilesOperations;
                }
                return null;
            }
            catch (InvalidSyntaxException e) {
                LOGGER.warning("Cannot load TilesOperations on JspMetadataListener.");
                return null;
            }
        }
        else {
            return tilesOperations;
        }
    }

    public TypeLocationService getTypeLocationService() {
        if (typeLocationService == null) {
            // Get all Services implement TypeLocationService interface
            try {
                ServiceReference<?>[] references = context
                        .getAllServiceReferences(
                                TypeLocationService.class.getName(), null);

                for (ServiceReference<?> ref : references) {
                    typeLocationService = (TypeLocationService) context
                            .getService(ref);
                    return typeLocationService;
                }
                return null;
            }
            catch (InvalidSyntaxException e) {
                LOGGER.warning("Cannot load TypeLocationService on JspMetadataListener.");
                return null;
            }
        }
        else {
            return typeLocationService;
        }
    }

    protected WebMetadataService getWebMetadataService() {
        if (webMetadataService == null) {
            // Get all Services implement WebMetadataService interface
            try {
                ServiceReference<?>[] references = context
                        .getAllServiceReferences(
                                WebMetadataService.class.getName(), null);

                for (ServiceReference<?> ref : references) {
                    webMetadataService = (WebMetadataService) context
                            .getService(ref);
                    return webMetadataService;
                }
                return null;
            }
            catch (InvalidSyntaxException e) {
                LOGGER.warning("Cannot load WebMetadataService on JspMetadataListener.");
                return null;
            }
        }
        else {
            return webMetadataService;
        }
    }

    public XmlRoundTripFileManager getXmlRoundTripFileManager() {
        if (xmlRoundTripFileManager == null) {
            // Get all Services implement XmlRoundTripFileManager interface
            try {
                ServiceReference<?>[] references = context
                        .getAllServiceReferences(
                                XmlRoundTripFileManager.class.getName(), null);

                for (ServiceReference<?> ref : references) {
                    xmlRoundTripFileManager = (XmlRoundTripFileManager) context
                            .getService(ref);
                    return xmlRoundTripFileManager;
                }
                return null;
            }
            catch (InvalidSyntaxException e) {
                LOGGER.warning("Cannot load XmlRoundTripFileManager on JspMetadataListener.");
                return null;
            }
        }
        else {
            return xmlRoundTripFileManager;
        }
    }
}