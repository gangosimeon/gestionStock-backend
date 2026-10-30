package com.geststock.integration;

import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drapeau de publication de l'entreprise courante ({@code integration_publication}, lot 2, étape G13 ; v12-D4,
 * v14-D11) : l'entreprise n'écrit d'événements que si elle a au moins une liaison active ou suspendue.
 *
 * <p><b>Course fermée</b> : chaque transaction qui publie lit le drapeau une seule fois, avec un verrou partagé
 * ({@code FOR SHARE}) gardé jusqu'à sa fin ; le passage à vrai ({@link #set}) prend un verrou exclusif sur la même
 * ligne, donc attend la fin de toutes les transactions qui l'ont lu à faux. Il a lieu avant l'activation de la liaison
 * (base plateforme) : quand la boutique peut lire son premier instantané, toute transaction qui a lu faux est validée
 * (l'instantané voit ses changements) et toute transaction plus récente lit vrai (elle publie). Les verrous partagés
 * ne se bloquent pas entre eux : les ventes concurrentes ne s'attendent pas.
 */
@Component
public class PublicationFlag {

	static final UUID ROW = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String READ = "select enabled from integration_publication where id = :id for share";

	private static final String WRITE = """
			update integration_publication set enabled = :enabled, updated_at = now() where id = :id""";

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	PublicationFlag(DataSource dataSource, PlatformTransactionManager transactionManager) {
		this.jdbc = JdbcClient.create(dataSource);
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Le drapeau est-il levé ? Lu une fois par transaction (celle de l'appelant, active), avec un verrou partagé gardé
	 * jusqu'à sa fin ; les lectures suivantes de la même transaction reprennent la valeur lue.
	 */
	boolean isOpen() {
		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			if (synchronization instanceof ReadFlag read) {
				return read.open;
			}
		}
		boolean open = this.jdbc.sql(READ).param("id", ROW).query(Boolean.class).optional().orElse(false);
		TransactionSynchronizationManager.registerSynchronization(new ReadFlag(open));
		return open;
	}

	/**
	 * Pose le drapeau de l'entreprise courante, dans sa propre transaction ; le verrou exclusif attend la fin des
	 * transactions qui l'ont lu (verrou partagé).
	 */
	public void set(boolean enabled) {
		this.transaction.executeWithoutResult(status -> this.jdbc.sql(WRITE).param("enabled", enabled).param("id", ROW).update());
	}

	/** Valeur lue par la transaction courante ; suspendue et oubliée avec elle. */
	private static final class ReadFlag implements TransactionSynchronization {

		private final boolean open;

		ReadFlag(boolean open) {
			this.open = open;
		}

	}

}
