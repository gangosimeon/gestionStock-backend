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
 *
 * <p><b>Attente équitable et bornée</b> : avec un simple {@code UPDATE}, PostgreSQL 17 laisse une nouvelle transaction
 * prendre le verrou partagé pendant que la mise à jour attend (observé) : un flux continu de ventes qui se chevauchent
 * affamerait l'activation. Le passage prend d'abord {@code LOCK TABLE … IN EXCLUSIVE MODE}, qui entre en conflit avec
 * le verrou de table {@code ROW SHARE} du {@code FOR SHARE} : la file des verrous de table fait attendre les ventes
 * qui arrivent derrière la demande déjà en attente (observé) ; le passage a lieu dès la fin des ventes en cours. Les
 * lectures simples (sans verrou) ne sont pas bloquées. L'attente est bornée ({@code lock_timeout},
 * {@code statement_timeout}, {@link PublicationFlagProperties}) : au-delà, une exception d'accès aux données ;
 * l'appelant refuse l'activation (503) ou journalise un WARN, et les ventes en attente reprennent.
 */
@Component
public class PublicationFlag {

	static final UUID ROW = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String READ = "select enabled from integration_publication where id = :id for share";

	private static final String WRITE = """
			update integration_publication set enabled = :enabled, updated_at = now() where id = :id""";

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final PublicationFlagProperties properties;

	PublicationFlag(DataSource dataSource, PlatformTransactionManager transactionManager,
			PublicationFlagProperties properties) {
		this.jdbc = JdbcClient.create(dataSource);
		this.properties = properties;
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
	 * transactions qui l'ont lu (verrou partagé), au plus {@code lock-timeout}.
	 * @throws org.springframework.dao.DataAccessException délai dépassé (verrou non obtenu) : rien n'est changé
	 */
	public void set(boolean enabled) {
		this.transaction.executeWithoutResult(status -> {
			long timeout = this.properties.lockTimeout().toMillis();
			this.jdbc.sql("set local lock_timeout = " + timeout).update();
			this.jdbc.sql("set local statement_timeout = " + timeout).update();
			// File équitable : EXCLUSIVE entre en conflit avec le ROW SHARE du FOR SHARE des ventes ; la demande en
			// attente passe avant les ventes qui arrivent après elle (observé), qui attendent derrière.
			this.jdbc.sql("lock table integration_publication in exclusive mode").update();
			this.jdbc.sql(WRITE).param("enabled", enabled).param("id", ROW).update();
		});
	}

	/** Valeur lue par la transaction courante ; suspendue et oubliée avec elle. */
	private static final class ReadFlag implements TransactionSynchronization {

		private final boolean open;

		ReadFlag(boolean open) {
			this.open = open;
		}

	}

}
