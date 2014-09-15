package me.geso.tinyorm;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import me.geso.tinyorm.meta.TableMeta;

/**
 * <pre>
 * {
 * 	&#064;code
 * 	class Foo extends BasicRow&lt;Foo&gt; {
 * 	}
 * }
 * </pre>
 *
 * @author Tokuhiro Matsuno
 * @param <Impl>
 *            The implementation class.
 */
// Note. I don't want to use Generics here. But I have no idea to create child
// instance.
@Slf4j
public abstract class BasicRow<Impl extends Row> implements Row {

	private Connection connection;
	private TableMeta tableMeta;

	/**
	 * Set connection to row object. Normally, you don't need to use this
	 * method.
	 */
	public void setConnection(Connection connection) {
		this.connection = connection;
	}

	/**
	 * Get connection from row object.
	 */
	protected Connection getConnection() {
		if (this.connection == null) {
			throw new RuntimeException(
					"This row object doesn't have a connection information.");
		}
		return this.connection;
	}

	/**
	 * Internal use.
	 * 
	 * @param orm
	 */
	public void setTableMeta(TableMeta tableMeta) {
		this.tableMeta = tableMeta;
	}

	protected TableMeta getTableMeta() {
		if (this.tableMeta == null) {
			throw new RuntimeException(
					"This row object doesn't have a tableMeta information.");
		}
		return this.tableMeta;
	}

	/**
	 * Get a where clause that selects the row from table. This method throws
	 * exception if the row doesn't have a primary key.
	 */
	public Query where() {
		Map<String, Object> pkmap = this.getTableMeta()
				.getPrimaryKeyValueMap(this);
		if (pkmap.isEmpty()) {
			throw new RuntimeException(
					"You can't delete row, doesn't have a primary keys.");
		}

		String sql = pkmap.keySet().stream().map(it
				-> "(" + quoteIdentifier(it) + "=?)"
				).collect(Collectors.joining(" AND "));
		List<Object> vars = pkmap.values().stream()
				.collect(Collectors.toList());
		this.validatePrimaryKeysForSelect(vars);
		return new Query(sql, vars);
	}

	/**
	 * This method validates primary keys for SELECT row from the table. You can
	 * override this method.
	 * 
	 * If you detected primary key constraints violation, you can throw the
	 * RuntimeException.
	 */
	protected void validatePrimaryKeysForSelect(List<Object> values) {
		for (Object value : values) {
			if (value == null) {
				throw new RuntimeException("Primary key should not be null: "
						+ this);
			}
		}

		/*
		 * 0 is a valid value for primary key. But, normally, it's just a bug.
		 * If you want to use 0 as a primary key value, please overwrite this
		 * method.
		 */
		if (values.size() == 1) {
			Object value = values.get(0);
			if ((value instanceof Integer && (((Integer) value) == 0))
					|| (value instanceof Long && (((Long) value) == 0))
					|| (value instanceof Short && (((Short) value) == 0))) {
				throw new RuntimeException("Primary key should not be zero: "
						+ value);
			}
		}
	}

	public void delete() {
		try {
			TableMeta tableMeta = this.getTableMeta();
			String tableName = tableMeta.getName();
			Query where = where();

			StringBuilder buf = new StringBuilder();
			buf.append("DELETE FROM ").append(quoteIdentifier(tableName))
					.append(" WHERE ");
			buf.append(where.getSQL());
			String sql = buf.toString();

			try (PreparedStatement preparedStatement = connection
					.prepareStatement(sql)) {
				TinyORMUtil.fillPreparedStatementParams(preparedStatement,
						where.getValues());
				int updated = preparedStatement
						.executeUpdate();
				if (updated != 1) {
					throw new RuntimeException("Cannot delete row: " + sql
							+ " "
							+ where.getValues());
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	public UpdateRowStatement createUpdateStatement() {
		UpdateRowStatement stmt = new UpdateRowStatement(this,
				this.getConnection(), this.getTableMeta());
		return stmt;
	}

	/**
	 * Update row's properties by bean. And send UPDATE statement to the server.
	 * 
	 * @param bean
	 */
	public void updateByBean(Object bean) {
		TableMeta tableMeta = this.getTableMeta();
		Map<String, Object> currentValueMap = tableMeta.getColumnValueMap(this);

		try {
			UpdateRowStatement stmt = new UpdateRowStatement(this,
					this.getConnection(), this.getTableMeta());
			BeanInfo beanInfo = Introspector.getBeanInfo(bean.getClass(),
					Object.class);
			PropertyDescriptor[] propertyDescriptors = beanInfo
					.getPropertyDescriptors();
			for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
				String name = propertyDescriptor.getName();
				if (!currentValueMap.containsKey(name)) {
					continue;
				}

				Object current = currentValueMap.get(name);
				Object newval = propertyDescriptor.getReadMethod().invoke(bean);
				if (newval != null) {
					if (!newval.equals(current)) {
						Object deflated = tableMeta.invokeDeflaters(name,
								newval);
						stmt.set(name, deflated);
						tableMeta.setValue(this, name, newval);
					}
				} else { // newval IS NULL.
					if (current != null) {
						stmt.set(name, null);
						tableMeta.setValue(this, name, null);
					}
				}
			}
			if (!stmt.hasSetClause()) {
				if (log.isDebugEnabled()) {
					log.debug("There is no modification: {} == {}",
							currentValueMap.toString(), bean.toString());
				}
				return; // There is no updates.
			}
			stmt.execute();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private String quoteIdentifier(String identifier) {
		return TinyORM.quoteIdentifier(identifier, this.getConnection());
	}

	/**
	 * Fetch the latest row data from database.
	 * 
	 * @return
	 */
	public Optional<Impl> refetch() {
		Query where = this.where();

		StringBuilder buf = new StringBuilder();
		buf.append("SELECT * FROM ").append(
				quoteIdentifier(this.getTableName()));
		buf.append(" WHERE ").append(where.getSQL());
		String sql = buf.toString();

		try {
			Connection connection = this.getConnection();
			Object[] params = where.getValues();
			try (PreparedStatement preparedStatement = connection
					.prepareStatement(sql)) {
				TinyORMUtil.fillPreparedStatementParams(preparedStatement,
						params);
				try (ResultSet rs = preparedStatement
						.executeQuery()) {
					if (rs.next()) {
						@SuppressWarnings("unchecked")
						Impl row = TinyORM.mapResultSet(
								(Class<Impl>) this.getClass(),
								rs, connection, this.getTableMeta());
						return Optional.of(row);
					} else {
						return Optional.empty();
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Get table name from the instance.
	 */
	protected String getTableName() {
		return this.getTableMeta().getName();
	}

}
