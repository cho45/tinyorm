/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package me.geso.tinyorm;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.beanutils.BeanUtilsBean;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.handlers.BeanHandler;

/**
 * <pre>
 * <code>
 * class Foo extends BasicRow<Foo> {
 * }
 * </code>
 * </pre>
 *
 * @author Tokuhiro Matsuno <tokuhirom@gmail.com>
 * @param <Impl>
 *            The implementation class.
 */
public abstract class BasicRow<Impl extends Row> implements Row {

	private Connection connection;

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
	public Connection getConnection() {
		if (this.connection == null) {
			throw new RuntimeException(
					"This row object doesn't have a connection information.");
		}
		return this.connection;
	}

	/**
	 * Get a where clause that selects the row from table. This method throws
	 * exception if the row doesn't have a primary key.
	 */
	public Query where() {
		List<String> primaryKeys = TinyORM.getPrimaryKeys(this.getClass());
		if (primaryKeys.isEmpty()) {
			throw new RuntimeException(
					"You can't delete row, doesn't have a primary keys.");
		}

		String sql = primaryKeys.stream().map(it
				-> "(" + quoteIdentifier(it) + "=?)"
				).collect(Collectors.joining(" AND "));
		List<Object> vars = primaryKeys
				.stream()
				.map(pk -> {
					try {
						Object value = BeanUtilsBean.getInstance()
								.getPropertyUtils().getProperty(this, pk);
						return value;
					} catch (IllegalArgumentException | IllegalAccessException
							| SecurityException | InvocationTargetException
							| NoSuchMethodException ex) {
						throw new RuntimeException(ex);
					}
				}).collect(Collectors.toList());
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
				throw new TinyORMException("Primary key should not be null: "
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
			if (value instanceof Long) {
				System.out.println(value.toString());
				long lvalue = (Long) value;
				if (lvalue == 0) {
					System.out.println("YAY");
				}
			}
			if ((value instanceof Integer && (((Integer) value) == 0))
					|| (value instanceof Long && (((Long) value) == 0))
					|| (value instanceof Short && (((Short) value) == 0))) {
				throw new TinyORMException("Primary key should not be zero: "
						+ value);
			}
		}
	}

	public void delete() {
		try {
			String table = TinyORM.getTableName(this.getClass());
			Query where = where();

			StringBuilder buf = new StringBuilder();
			buf.append("DELETE FROM ").append(quoteIdentifier(table))
					.append(" WHERE ");
			buf.append(where.getSQL());
			String sql = buf.toString();

			int updated = new QueryRunner().update(getConnection(), sql, where
					.getValues());
			if (updated != 1) {
				throw new RuntimeException("Cannot delete row: " + sql + " "
						+ where.getValues());
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Create UpdateRowStatement object for send UPDATE statement.
	 * 
	 * @return
	 */
	public UpdateRowStatement update() {
		return new UpdateRowStatement(this);
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
			@SuppressWarnings("unchecked")
			Impl row = new QueryRunner().query(this.getConnection(), sql,
					new BeanHandler<>((Class<Impl>) this.getClass()), where
							.getValues());
			return Optional.ofNullable(row);
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Get table name from the instance.
	 */
	public String getTableName() {
		return TinyORM.getTableName(this.getClass());
	}

}
