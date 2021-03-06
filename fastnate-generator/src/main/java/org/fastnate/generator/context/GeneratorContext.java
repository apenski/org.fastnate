package org.fastnate.generator.context;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.SequenceGenerator;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang.StringUtils;
import org.fastnate.generator.EntitySqlGenerator;
import org.fastnate.generator.dialect.GeneratorDialect;
import org.fastnate.generator.dialect.H2Dialect;
import org.fastnate.generator.provider.HibernateProvider;
import org.fastnate.generator.provider.JpaProvider;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Represents the configuration and state for one or more {@link EntitySqlGenerator}s.
 *
 * @author Tobias Liefke
 */
@Getter
@Setter
@Slf4j
public class GeneratorContext {

	/** The settings key for the {@link #provider}. */
	public static final String PROVIDER_KEY = "fastnate.generator.jpa.provider";

	/** The settings key for the path to the persistence.xml, either relative to the current directory or absolute. */
	public static final String PERSISTENCE_FILE_KEY = "fastnate.generator.persistence.file";

	/**
	 * The settings key for the name of the persistence unit in the persistence.xml. The first is used, if none is
	 * given.
	 */
	public static final String PERSISTENCE_UNIT_KEY = "fastnate.generator.persistence.unit";

	/** The settings key for the {@link #dialect}. */
	public static final String DIALECT_KEY = "fastnate.generator.dialect";

	/** The settings key for {@link #writeNullValues}. */
	public static final String NULL_VALUES_KEY = "fastnate.generator.null.values";

	/** The settings key for {@link #explicitIds}. */
	public static final String EXPLICIT_IDS_KEY = "fastnate.generator.explicit.ids";

	/** The settings key for the {@link #uniquePropertyQuality}. */
	public static final String UNIQUE_PROPERTIES_QUALITY_KEY = "fastnate.generator.unique.properties.quality";

	/** The settings key for the {@link #maxUniqueProperties}. */
	public static final String UNIQUE_PROPERTIES_MAX_KEY = "fastnate.generator.unique.properties.max";

	/** The settings key for {@link #preferSequenceCurentValue}. */
	public static final String PREFER_SEQUENCE_CURRENT_VALUE = "fastnate.generator.prefer.sequence.current.value";

	/**
	 * Tries to read any persistence file defined in the settings.
	 *
	 * @param settings
	 *            the current settings
	 */
	private static void readPersistenceFile(final Properties settings) {
		String persistenceFilePath = settings.getProperty(PERSISTENCE_FILE_KEY);
		if (StringUtils.isEmpty(persistenceFilePath)) {
			final URL url = GeneratorContext.class.getResource("/META-INF/persistence.xml");
			if (url == null) {
				return;
			}
			persistenceFilePath = url.toString();
		} else {
			final File persistenceFile = new File(persistenceFilePath);
			if (persistenceFile.isFile()) {
				persistenceFilePath = persistenceFile.toURI().toString();
			}
		}

		final String persistenceUnit = settings.getProperty(PERSISTENCE_UNIT_KEY);
		try {
			final Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
					.parse(persistenceFilePath);
			final NodeList persistenceUnits = document.getElementsByTagName("persistence-unit");
			for (int i = 0; i < persistenceUnits.getLength(); i++) {
				final Element persistenceUnitElement = (Element) persistenceUnits.item(i);
				if (StringUtils.isEmpty(persistenceUnit)
						|| persistenceUnit.equals(persistenceUnitElement.getAttribute("name"))) {
					final NodeList properties = persistenceUnitElement.getElementsByTagName("property");
					for (int i2 = 0; i2 < properties.getLength(); i2++) {
						final Element property = (Element) properties.item(i2);
						final String name = property.getAttribute("name");
						if (!settings.containsKey(name)) {
							settings.put(name, property.getAttribute("value"));
						}
					}
					break;
				}
			}
		} catch (final IOException | SAXException | ParserConfigurationException e) {
			log.error("Could not read " + persistenceFilePath + ": " + e, e);
		}
	}

	/** Contains the extracted metadata per entity class. */
	private final Map<Class<?>, EntityClass<?>> descriptions = new HashMap<>();

	/**
	 * Identifies the SQL dialect for generating SQL statements. Encapsulates the database specifica.
	 */
	private GeneratorDialect dialect;

	/**
	 * Identifies the JPA provider to indicate implementation specific details.
	 */
	private JpaProvider provider;

	/**
	 * The maximum count of columns that are used when referencing an entity using it's unique properties.
	 */
	private int maxUniqueProperties = 1;

	/**
	 * Indicates what kind of properties are used for referencing an entity with its unique properties.
	 */
	private UniquePropertyQuality uniquePropertyQuality = UniquePropertyQuality.onlyRequiredPrimitives;

	/**
	 * Indiciates to use "currval" of a sequence if the referenced entity is the last created entity for that sequence
	 * before checking for {@link #uniquePropertyQuality unique properties}.
	 */
	private boolean preferSequenceCurentValue = true;

	/**
	 * In bulk mode, IDs are not generated by the DB, but by the generator. In that case, IDs are explicitly generated
	 * starting from 0.
	 */
	private boolean explicitIds;

	/**
	 * Indicates to include null values explictly in statements.
	 */
	private boolean writeNullValues;

	/** Contains the current values for all {@link SequenceGenerator sequences}. */
	private final Map<String, Long> sequences = new HashMap<>();

	/** Contains the current values for {@link GeneratedValue ids} with {@link GenerationType#IDENTITY}. */
	private final Map<String, Long> ids = new HashMap<>();

	/** Contains the state of single entities, maps from an entity name to the mapping of an id to its state. */
	private final Map<String, Map<Object, GenerationState>> states = new HashMap<>();

	/** Contains the settings that where given during creation. Empty if none were given. */
	private final Properties settings;

	/**
	 * Creates a default generator context.
	 */
	public GeneratorContext() {
		this(new H2Dialect());
	}

	/**
	 * Creates a generator context for a dialect.
	 *
	 * @param dialect
	 *            the database dialect to use during generation
	 */
	public GeneratorContext(final GeneratorDialect dialect) {
		this.dialect = dialect;
		this.provider = new HibernateProvider();
		this.settings = new Properties();
	}

	/**
	 * Creates a new instance of {@link GeneratorContext}.
	 *
	 * @param settings
	 *            contains the settings
	 */
	public GeneratorContext(final Properties settings) {
		this.settings = settings;

		readPersistenceFile(settings);

		String providerName = settings.getProperty(PROVIDER_KEY, "HibernateProvider");
		if (providerName.indexOf('.') < 0) {
			providerName = JpaProvider.class.getPackage().getName() + '.' + providerName;
		}
		try {
			this.provider = (JpaProvider) Class.forName(providerName).newInstance();
			this.provider.initialize(settings);
		} catch (final InstantiationException | IllegalAccessException | ClassNotFoundException
				| ClassCastException e) {
			throw new IllegalArgumentException("Can't instantiate provider: " + providerName, e);
		}

		String dialectName = settings.getProperty(DIALECT_KEY, "H2Dialect");
		if (dialectName.indexOf('.') < 0) {
			dialectName = GeneratorDialect.class.getPackage().getName() + '.' + dialectName;
			if (!dialectName.endsWith("Dialect")) {
				dialectName += "Dialect";
			}
		}
		try {
			this.dialect = (GeneratorDialect) Class.forName(dialectName).newInstance();
		} catch (final InstantiationException | IllegalAccessException | ClassNotFoundException
				| ClassCastException e) {
			throw new IllegalArgumentException("Can't instantiate dialect: " + dialectName, e);
		}

		this.explicitIds = Boolean
				.parseBoolean(settings.getProperty(EXPLICIT_IDS_KEY, String.valueOf(this.explicitIds)));
		this.writeNullValues = Boolean
				.parseBoolean(settings.getProperty(NULL_VALUES_KEY, String.valueOf(this.writeNullValues)));
		this.uniquePropertyQuality = UniquePropertyQuality
				.valueOf(settings.getProperty(UNIQUE_PROPERTIES_QUALITY_KEY, this.uniquePropertyQuality.name()));
		this.maxUniqueProperties = Integer
				.parseInt(settings.getProperty(UNIQUE_PROPERTIES_MAX_KEY, String.valueOf(this.maxUniqueProperties)));
		this.preferSequenceCurentValue = Boolean.parseBoolean(
				settings.getProperty(PREFER_SEQUENCE_CURRENT_VALUE, String.valueOf(this.preferSequenceCurentValue)));
	}

	/**
	 * Creates the next value for a generated column (and remembers that value).
	 *
	 * @param property
	 *            the property
	 * @return the created value
	 */
	public Long createNextValue(final GeneratedIdProperty<?> property) {
		if (property.getGenerator() != null) {
			return createNextValue(property.getGenerator());
		}
		final String columnId = property.getTable() + "." + property.getColumn();
		final Long currentValue = this.ids.get(columnId);
		final Long newValue;
		if (currentValue == null) {
			newValue = 0L;
		} else {
			newValue = currentValue + 1;
		}
		this.ids.put(columnId, newValue);
		return newValue;
	}

	/**
	 * Creates the next value for a sequence (and remembers that value).
	 *
	 * @param generator
	 *            the generator of the current column
	 * @return the created value
	 */
	public Long createNextValue(final SequenceGenerator generator) {
		final String sequenceName = generator.sequenceName();
		final Long currentValue = this.sequences.get(sequenceName);
		final Long newValue;
		if (currentValue == null) {
			newValue = (long) generator.initialValue();
		} else {
			// Allocation size is _not_ necessarily the increment size
			// As soon as we read hibernate specific properties, we can read the increment size as well
			newValue = currentValue + generator.allocationSize();
		}
		this.sequences.put(sequenceName, newValue);
		return newValue;
	}

	/**
	 * Resolves the current value for a generated column.
	 *
	 * @param property
	 *            the property of the column
	 * @return the current value or {@code null} if no row was created up to now
	 */
	public Long getCurrentValue(final GeneratedIdProperty<?> property) {
		if (property.getGenerator() != null) {
			return getCurrentValue(property.getGenerator());
		}
		final String columnId = property.getTable() + "." + property.getColumn();
		final Long currentId = this.ids.get(columnId);
		if (currentId == null) {
			throw new IllegalArgumentException("No current value for: " + columnId);
		}
		return currentId;
	}

	/**
	 * Resolves the current value for a sequence.
	 *
	 * @param generator
	 *            the generator of the current column
	 * @return the current value or {@code null} if the sequence was not used up to now
	 */
	public Long getCurrentValue(final SequenceGenerator generator) {
		return this.sequences.get(generator.sequenceName());
	}

	/**
	 * Finds the description for a class.
	 *
	 * @param entityClass
	 *            the class to lookup
	 * @return the description for the class or {@code null} if the class is not an {@link Entity}
	 */
	@SuppressWarnings("unchecked")
	public <E> EntityClass<E> getDescription(final Class<E> entityClass) {
		// Lookup description
		EntityClass<E> description = (EntityClass<E>) this.descriptions.get(entityClass);
		if (description == null) {
			if (entityClass.isAnnotationPresent(Entity.class)) {
				// Description not build up to now

				// Create the description
				description = new EntityClass<>(this, entityClass);

				// First remember the description (to prevent endless loops)
				this.descriptions.put(entityClass, description);

				// And now build the properties
				description.build();
			} else {
				// Step up to find the parent description
				final Class<?> superClass = entityClass.getSuperclass();
				if (superClass == null) {
					return null;
				}

				description = (EntityClass<E>) getDescription(superClass);
				if (description != null) {
					// Just remember description for our subclass
					this.descriptions.put(entityClass, description);
				}
			}

		}
		return description;
	}

	/**
	 * Finds the description for the class of an entity.
	 *
	 * @param entity
	 *            the entity to lookup
	 * @return the description for the class of the entity
	 * @throws IllegalArgumentException
	 *             if the given object is no {@link Entity}
	 */
	@SuppressWarnings("unchecked")
	public <E> EntityClass<E> getDescription(final E entity) {
		if (entity == null) {
			throw new IllegalArgumentException("Can't inspect null entity");
		}
		final EntityClass<E> description = (EntityClass<E>) getDescription(entity.getClass());
		if (description == null) {
			throw new IllegalArgumentException(entity.getClass() + " is not an entity class");
		}
		return description;
	}

	/**
	 * The entity states for the given entity class.
	 *
	 * @param entityClass
	 *            the current entity class
	 * @return the states of the entities of that class (with their IDs as keys)
	 */
	Map<Object, GenerationState> getStates(final EntityClass<?> entityClass) {
		Map<Object, GenerationState> entityStates = this.states.get(entityClass.getEntityName());
		if (entityStates == null) {
			entityStates = new HashMap<>();
			this.states.put(entityClass.getEntityName(), entityStates);
		}
		return entityStates;
	}

}
